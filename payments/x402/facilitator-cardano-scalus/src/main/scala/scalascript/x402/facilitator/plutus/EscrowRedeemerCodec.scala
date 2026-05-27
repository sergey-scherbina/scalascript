package scalascript.x402.facilitator.plutus

import com.bloxbean.cardano.client.plutus.spec.{BytesPlutusData, ConstrPlutusData, PlutusData}
import scalascript.x402.CardanoPaymentProof

/** Off-chain Plutus redeemer encoding for the x402 escrow validator. */
object EscrowRedeemerCodec:

  /** `EscrowRedeemer.Claim(coseSign1Bytes, coseKeyBytes)`.
   *
   *  The Scalus validator declares `Claim` as the first enum case, so
   *  the constructor alternative is 0. */
  def claim(proof: CardanoPaymentProof): PlutusData =
    ConstrPlutusData.of(
      0L,
      BytesPlutusData.of(hexToBytes(proof.signature)),
      BytesPlutusData.of(hexToBytes(proof.key)),
    )

  private[plutus] def hexToBytes(hex: String): Array[Byte] =
    val h = if hex.startsWith("0x") then hex.drop(2) else hex
    require(h.length % 2 == 0, s"hex string must have even length, got ${h.length}")
    require(h.matches("(?i)[0-9a-f]*"), s"hex string contains non-hex characters: '${h.take(32)}'")
    h.grouped(2).map(Integer.parseInt(_, 16).toByte).toArray
