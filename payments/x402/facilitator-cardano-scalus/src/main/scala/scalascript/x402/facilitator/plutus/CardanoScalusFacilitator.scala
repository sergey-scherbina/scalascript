package scalascript.x402.facilitator.plutus

import scalascript.x402.facilitator.{CardanoFacilitator, CardanoFacilitatorConfig, CardanoNetwork, CardanoProvider}
import scalascript.x402.Facilitator
import scala.concurrent.ExecutionContext

/** Entry point for x402 Cardano facilitators that use the Plutus-escrow
 *  settlement path (Phase 10+). Wires a [[ScalusSettler]] into
 *  [[CardanoFacilitatorConfig.scalusSettle]] so the base facilitator can
 *  call it without taking a compile-time dependency on bloxbean / Scalus. */
object CardanoScalusFacilitator:

  def preprod(
    receiverAddress: String,
    config:          ScalusSettlerConfig,
    builder:         ClaimTxBuilder = BloxbeanClaimTxBuilder.unimplemented,
  )(using ExecutionContext): Facilitator =
    build(CardanoNetwork.Preprod, receiverAddress, config, builder)

  def mainnet(
    receiverAddress: String,
    config:          ScalusSettlerConfig,
    builder:         ClaimTxBuilder = BloxbeanClaimTxBuilder.unimplemented,
  )(using ExecutionContext): Facilitator =
    build(CardanoNetwork.Mainnet, receiverAddress, config, builder)

  private def build(
    network:         CardanoNetwork,
    receiverAddress: String,
    config:          ScalusSettlerConfig,
    builder:         ClaimTxBuilder,
  )(using ExecutionContext): Facilitator =
    val settler = if network == CardanoNetwork.Preprod
      then ScalusSettler.preprod(config, builder)
      else ScalusSettler.mainnet(config, builder)
    val facConfig = CardanoFacilitatorConfig(
      network         = network,
      provider        = CardanoProvider.Scalus(nodeSocket = ""),
      receiverAddress = receiverAddress,
      scalusSettle    = Some(ScalusSettler.asConfigHook(settler)),
    )
    CardanoFacilitator(facConfig, config.blockfrost)
