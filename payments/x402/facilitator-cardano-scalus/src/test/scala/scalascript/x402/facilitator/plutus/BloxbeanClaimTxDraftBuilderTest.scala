package scalascript.x402.facilitator.plutus

import com.bloxbean.cardano.client.plutus.spec.{ConstrPlutusData, RedeemerTag}
import com.bloxbean.cardano.client.spec.NetworkId
import com.bloxbean.cardano.client.transaction.spec.Transaction
import org.scalatest.funsuite.AnyFunSuite
import scalascript.blockfrost.BlockfrostProtocolParams
import scalascript.x402.{Network, ScalusEscrowRef}

import scala.concurrent.{Await, Future}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.*

class BloxbeanClaimTxDraftBuilderTest extends AnyFunSuite:

  private val plan = ClaimTxPlan(
    network         = Network.CardanoPreprod,
    escrowRef       = ScalusEscrowRef("a" * 64, 2),
    scriptAddress   = EscrowScript.address(Network.CardanoPreprod),
    receiverAddress = EscrowScript.address(Network.CardanoPreprod),
    lovelace        = BigInt(2_000_000),
    coseSign1Hex    = "c0ffee",
    coseKeyHex      = "cafe",
    relayerKeyHex   = "11" * 32,
    collateralRef   = Some(ScalusEscrowRef("b" * 64, 3)),
    requiredSigner  = Some(Array.fill[Byte](28)(0x42.toByte)),
    feeLovelace     = BigInt(170_000),
    ttlSlot         = Some(123456L),
    validityStart   = Some(123000L),
    claimExUnits    = ScalusExUnits(mem = BigInt(1000), steps = BigInt(2000)),
  )

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

  test("draft builder serializes a bloxbean Transaction skeleton") {
    val bytes = BloxbeanClaimTxDraftBuilder.buildTransaction(plan).serialize()
    assert(bytes.nonEmpty)

    val tx = Transaction.deserialize(bytes)
    assert(tx.getBody.getNetworkId == NetworkId.TESTNET)
    assert(tx.getBody.getInputs.size == 1)
    assert(tx.getBody.getInputs.get(0).getTransactionId == "a" * 64)
    assert(tx.getBody.getInputs.get(0).getIndex == 2)
    assert(tx.getBody.getCollateral.size == 1)
    assert(tx.getBody.getCollateral.get(0).getTransactionId == "b" * 64)
    assert(tx.getBody.getCollateral.get(0).getIndex == 3)
    assert(tx.getBody.getRequiredSigners.size == 1)
    assert(tx.getBody.getRequiredSigners.get(0).toSeq == Array.fill[Byte](28)(0x42.toByte).toSeq)
    assert(tx.getBody.getFee.toString == "170000")
    assert(tx.getBody.getTtl == 123456L)
    assert(tx.getBody.getValidityStartInterval == 123000L)
    assert(tx.getBody.getScriptDataHash.length == 32)
    assert(tx.getBody.getOutputs.size == 1)
    assert(tx.getBody.getOutputs.get(0).getAddress == plan.receiverAddress)
    assert(tx.getBody.getOutputs.get(0).getValue.getCoin.toString == "2000000")

    assert(tx.getWitnessSet.getVkeyWitnesses.size == 1)
    assert(tx.getWitnessSet.getVkeyWitnesses.get(0).getVkey.length == 32)
    assert(tx.getWitnessSet.getVkeyWitnesses.get(0).getSignature.length == 64)
    assert(tx.getWitnessSet.getPlutusV3Scripts.size == 1)
    assert(tx.getWitnessSet.getRedeemers.size == 1)
    val redeemer = tx.getWitnessSet.getRedeemers.get(0)
    assert(redeemer.getTag == RedeemerTag.Spend)
    assert(redeemer.getIndex.intValue == 0)
    assert(redeemer.getData.asInstanceOf[ConstrPlutusData].getAlternative == 0L)
    assert(redeemer.getExUnits.getMem == BigInt(1000).bigInteger)
    assert(redeemer.getExUnits.getSteps == BigInt(2000).bigInteger)
  }

  test("balanced draft estimates fee from protocol params and serialized size") {
    val tx = BloxbeanClaimTxDraftBuilder.buildBalancedTransaction(plan.copy(feeLovelace = BigInt(0)), params)
    val expected = ScalusFeeBalancer.estimate(
      params,
      tx.serialize().length,
      Seq(plan.claimExUnits.mem -> plan.claimExUnits.steps),
    ).total
    assert(tx.getBody.getFee.toString == expected.toString)
  }

  test("draftBalanced builder fetches protocol params before serializing") {
    var fetched = false
    val builder = BloxbeanClaimTxBuilder.draftBalanced { () =>
      fetched = true
      Future.successful(params)
    }
    val bytes = Await.result(builder.build(plan.copy(feeLovelace = BigInt(0))), 5.seconds)
    val tx = Transaction.deserialize(bytes)

    assert(fetched)
    val expectedTx = BloxbeanClaimTxDraftBuilder.buildBalancedTransaction(plan.copy(feeLovelace = BigInt(0)), params)
    assert(tx.getBody.getFee == expectedTx.getBody.getFee)
  }
