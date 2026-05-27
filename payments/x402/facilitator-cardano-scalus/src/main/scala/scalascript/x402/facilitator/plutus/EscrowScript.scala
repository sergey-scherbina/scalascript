package scalascript.x402.facilitator.plutus

import org.bouncycastle.crypto.digests.Blake2bDigest
import scalascript.blockchain.cardano.Bech32
import scalascript.x402.Network

/** Public off-chain identity of the compiled x402 Plutus escrow script. */
object EscrowScript:

  /** Blake2b-224 script credential of the committed Plutus validator. */
  lazy val scriptHash: Array[Byte] =
    blake2b224(X402EscrowCompiled.doubleCborBytes)

  /** CIP-19 enterprise script address for the compiled validator.
   *
   *  `CardanoMainnet` uses network id 1. `CardanoPreprod` and
   *  `CardanoPreview` both use network id 0; their separation is at the
   *  chain configuration layer, not in the bech32 address payload. */
  def address(network: Network): String =
    val testnet = network match
      case Network.CardanoMainnet => false
      case Network.CardanoPreprod | Network.CardanoPreview => true
      case other =>
        throw IllegalArgumentException(s"EscrowScript.address requires a Cardano network, got $other")
    address(testnet)

  /** CIP-19 enterprise script address for the compiled validator. */
  def address(testnet: Boolean): String =
    val networkId = if testnet then 0 else 1
    val header    = ((7 << 4) | networkId).toByte
    val hrp       = if testnet then "addr_test" else "addr"
    Bech32.encode(hrp, header +: scriptHash)

  private def blake2b224(bytes: Array[Byte]): Array[Byte] =
    val digest = Blake2bDigest(224)
    digest.update(bytes, 0, bytes.length)
    val out = new Array[Byte](28)
    digest.doFinal(out, 0)
    out
