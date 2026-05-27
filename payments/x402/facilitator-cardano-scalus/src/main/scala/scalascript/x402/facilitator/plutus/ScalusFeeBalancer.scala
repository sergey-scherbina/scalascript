package scalascript.x402.facilitator.plutus

import scalascript.blockfrost.BlockfrostProtocolParams

case class ScalusFeeEstimate(
  txSizeBytes: BigInt,
  linearFee:   BigInt,
  scriptFee:   BigInt,
):
  def total: BigInt = linearFee + scriptFee

object ScalusFeeBalancer:
  def estimate(
    params:      BlockfrostProtocolParams,
    txSizeBytes: Int,
    exUnits:     Seq[(BigInt, BigInt)] = Nil,
  ): ScalusFeeEstimate =
    val linear = params.minFeeA * BigInt(txSizeBytes) + params.minFeeB
    val script = exUnits.foldLeft(BigInt(0)) { case (acc, (mem, steps)) =>
      acc + ceil(params.priceMem * BigDecimal(mem)) + ceil(params.priceStep * BigDecimal(steps))
    }
    ScalusFeeEstimate(BigInt(txSizeBytes), linear, script)

  private def ceil(value: BigDecimal): BigInt =
    value.setScale(0, BigDecimal.RoundingMode.CEILING).toBigInt
