package scalascript.x402.facilitator.plutus

import com.bloxbean.cardano.client.api.impl.StaticTransactionEvaluator
import com.bloxbean.cardano.client.plutus.spec.ExUnits
import org.scalatest.funsuite.AnyFunSuite
import scalascript.blockfrost.BlockfrostProtocolParams
import scalascript.x402.{Network, ScalusEscrowRef}

import java.math.BigInteger
import java.util.Collections
import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.*

class ScalusTxEvaluatorTest extends AnyFunSuite:

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

  private val plan = ClaimTxPlan(
    network         = Network.CardanoPreprod,
    escrowRef       = ScalusEscrowRef("a" * 64, 2),
    scriptAddress   = EscrowScript.address(Network.CardanoPreprod),
    receiverAddress = "addr_test1wzj0t77w5k08xqpsslzw4rljksp7ev9stduxrzqgyg7w35qqkq0cd",
    lovelace        = BigInt(2_000_000),
    coseSign1Hex    = "c0ffee",
    coseKeyHex      = "cafe",
    relayerKeyHex   = "11" * 32,
  )

  test("bloxbean evaluator adapts EvaluationResult into ScalusExUnits") {
    val evaluator = ScalusTxEvaluator.bloxbean(new StaticTransactionEvaluator(Collections.singletonList(
      ExUnits.builder().mem(BigInteger.valueOf(1234)).steps(BigInteger.valueOf(5678)).build()
    )))
    val tx = BloxbeanClaimTxDraftBuilder.buildTransaction(plan)
    val results = Await.result(evaluator.evaluate(tx), 5.seconds)

    assert(ScalusTxEvaluator.claimSpendExUnits(results).contains(ScalusExUnits(BigInt(1234), BigInt(5678))))
  }

  test("evaluated balanced draft rebuilds redeemer and fee from evaluator ex-units") {
    val evaluator = ScalusTxEvaluator.bloxbean(new StaticTransactionEvaluator(Collections.singletonList(
      ExUnits.builder().mem(BigInteger.valueOf(1000)).steps(BigInteger.valueOf(2000)).build()
    )))
    val tx = Await.result(BloxbeanClaimTxDraftBuilder.buildEvaluatedBalancedTransaction(plan, params, evaluator), 5.seconds)
    val redeemer = tx.getWitnessSet.getRedeemers.get(0)
    val expectedFee = ScalusFeeBalancer.estimate(params, tx.serialize().length, Seq(BigInt(1000) -> BigInt(2000))).total

    assert(redeemer.getExUnits.getMem == BigInteger.valueOf(1000))
    assert(redeemer.getExUnits.getSteps == BigInteger.valueOf(2000))
    assert(tx.getBody.getFee == expectedFee.bigInteger)
  }
