package scalascript.blockchain.cardano

import scalascript.crypto.{Blake2b, Curve, PublicKey}

/** Cardano address utilities — CIP-19.
 *
 *  Two address shapes are supported:
 *
 *  - **Enterprise** (type 6/7): `header || payment_keyhash`. 29 bytes
 *    total. Header `0x60` mainnet, `0x70` testnet.
 *  - **Base** (type 0/1): `header || payment_keyhash || stake_keyhash`.
 *    57 bytes total. Header `0x00` mainnet, `0x10` testnet.
 *
 *  Both layouts use Blake2b-224 of the Ed25519 verification key for
 *  each keyhash slot. The 28-byte hash is bech32-encoded with HRP
 *  `addr` (mainnet) / `addr_test` (testnet).
 *
 *  Reward (stake) and script-based variants (CIP-19 types 2-5, 14-15)
 *  are out of scope; the host-supplied address constructor on
 *  `Wallets.cardano(..., address, ...)` handles them. */
object CardanoAddress:

  // ── Enterprise (payment-only) ─────────────────────────────────────────────

  def fromPublicKey(pk: PublicKey, testnet: Boolean = false): String =
    require(pk.curve == Curve.Ed25519, s"Cardano addresses require Ed25519, got ${pk.curve}")
    val keyHash   = blake2b224(pk.bytes)
    val header    = if testnet then 0x70.toByte else 0x60.toByte
    val addrBytes = header +: keyHash
    val hrp       = if testnet then "addr_test" else "addr"
    Bech32.encode(hrp, addrBytes)

  // ── Base (payment + stake) ────────────────────────────────────────────────

  /** Build a CIP-19 type-0 base address from a payment verification key
   *  and a stake verification key. Both must be raw Ed25519 keys.
   *
   *  Layout: `header || Blake2b-224(payment) || Blake2b-224(stake)`,
   *  bech32-encoded with HRP `addr` / `addr_test`. */
  def fromPublicKeys(payment: PublicKey, stake: PublicKey, testnet: Boolean = false): String =
    require(payment.curve == Curve.Ed25519, s"payment key must be Ed25519, got ${payment.curve}")
    require(stake.curve   == Curve.Ed25519, s"stake key must be Ed25519, got ${stake.curve}")
    val paymentHash = blake2b224(payment.bytes)
    val stakeHash   = blake2b224(stake.bytes)
    val header      = if testnet then 0x10.toByte else 0x00.toByte
    val addrBytes   = (header +: paymentHash) ++ stakeHash
    val hrp         = if testnet then "addr_test" else "addr"
    Bech32.encode(hrp, addrBytes)

  // ── Validation / decoding ────────────────────────────────────────────────

  def isValid(s: String): Boolean =
    (s.startsWith("addr1") || s.startsWith("addr_test1")) &&
    Bech32.decode(s).isRight

  def normalize(s: String): String = s.toLowerCase

  /** Extract the raw bytes (header + payload) from a bech32 address. */
  def toBytes(s: String): Array[Byte] =
    Bech32.decode(s).getOrElse(throw new IllegalArgumentException(s"Invalid Cardano address: $s"))

  /** CIP-19 address kind, derived from the header byte's high nibble.
   *  Exposed so consumers can sanity-check that a user-supplied bech32
   *  string is the kind they expect (payment-only vs. payment+stake). */
  enum Kind:
    case Base, Enterprise, Reward, Pointer, Script, Other

  def kindOf(s: String): Kind =
    val bytes = toBytes(s)
    if bytes.isEmpty then Kind.Other
    else (bytes(0) & 0xF0) match
      case 0x00 | 0x10 => Kind.Base
      case 0x20 | 0x30 => Kind.Base       // script-payment + key-stake variants
      case 0x40 | 0x50 => Kind.Pointer
      case 0x60 | 0x70 => Kind.Enterprise
      case 0xE0 | 0xF0 => Kind.Reward
      case _           => Kind.Other

  // Cardano hashes the Ed25519 key with BLAKE2b-224 (CIP-19); the portable reference is
  // backend-agnostic, so this module needs no `org.bouncycastle` dependency and cross-compiles to JS.
  private def blake2b224(data: Array[Byte]): Array[Byte] = Blake2b.hash224(data)
