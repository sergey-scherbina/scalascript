package scalascript.x402.facilitator.plutus

import org.scalatest.funsuite.AnyFunSuite
import scalascript.blockfrost.BlockfrostProtocolParams

class ScalusFeeBalancerTest extends AnyFunSuite:

  private val params = BlockfrostProtocolParams(
    minFeeA             = BigInt(44),
    minFeeB             = BigInt(155381),
    maxTxSize           = BigInt(16384),
    priceMem            = BigDecimal("0.0577"),
    priceStep           = BigDecimal("0.0000721"),
    coinsPerUtxoSize    = BigInt(4310),
    collateralPercent   = 150,
    maxCollateralInputs = 3,
    costModels          = Map.empty,
  )

  test("estimate: applies linear min-fee formula") {
    val estimate = ScalusFeeBalancer.estimate(params, txSizeBytes = 500)
    assert(estimate.txSizeBytes == BigInt(500))
    assert(estimate.linearFee == BigInt(177381))
    assert(estimate.scriptFee == BigInt(0))
    assert(estimate.total == BigInt(177381))
  }

  test("estimate: includes rounded-up script ex-unit fee when provided") {
    val estimate = ScalusFeeBalancer.estimate(params, txSizeBytes = 500, exUnits = Seq(BigInt(1000) -> BigInt(2000)))
    assert(estimate.linearFee == BigInt(177381))
    assert(estimate.scriptFee == BigInt(59))
    assert(estimate.total == BigInt(177440))
  }
