package scalascript.blockchain.cardano

import org.bouncycastle.crypto.digests.Blake2bDigest
import scalascript.crypto.{Curve, PublicKey}

/** Cardano address utilities — enterprise addresses only (CIP-19 type 6/7).
 *
 *  Enterprise address layout:
 *    header_byte || payment_keyhash (28 bytes Blake2b-224 of Ed25519 pubkey)
 *
 *  Header:
 *    0x60 = mainnet enterprise
 *    0x70 = testnet enterprise (preprod / preview)
 *
 *  Encoding: bech32 with HRP "addr" (mainnet) / "addr_test" (testnet). */
object CardanoAddress:

  def fromPublicKey(pk: PublicKey, testnet: Boolean = false): String =
    require(pk.curve == Curve.Ed25519, s"Cardano addresses require Ed25519, got ${pk.curve}")
    val keyHash  = blake2b224(pk.bytes)
    val header   = if testnet then 0x70.toByte else 0x60.toByte
    val addrBytes = header +: keyHash
    val hrp       = if testnet then "addr_test" else "addr"
    Bech32.encode(hrp, addrBytes)

  def isValid(s: String): Boolean =
    (s.startsWith("addr1") || s.startsWith("addr_test1")) &&
    Bech32.decode(s).isRight

  def normalize(s: String): String = s.toLowerCase

  /** Extract the raw bytes (header + payload) from a bech32 address. */
  def toBytes(s: String): Array[Byte] =
    Bech32.decode(s).getOrElse(throw new IllegalArgumentException(s"Invalid Cardano address: $s"))

  private def blake2b224(data: Array[Byte]): Array[Byte] =
    val digest = Blake2bDigest(224)
    digest.update(data, 0, data.length)
    val out = new Array[Byte](28)
    digest.doFinal(out, 0)
    out
