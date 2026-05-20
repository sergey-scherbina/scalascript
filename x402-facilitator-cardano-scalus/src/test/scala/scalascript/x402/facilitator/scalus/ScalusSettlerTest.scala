package scalascript.x402.facilitator.scalus

import scalascript.x402.*
import scalascript.x402.facilitator.{
  CardanoFacilitator, CardanoFacilitatorConfig, CardanoNetwork, CardanoProvider,
}
import scalascript.blockfrost.{AddressInfo, BlockfrostClient, BlockfrostUtxo}
import org.scalatest.funsuite.AnyFunSuite
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*
import ExecutionContext.Implicits.global

private trait MockBlockfrostBase extends BlockfrostClient:
  def getAddressInfo(a: String): Future[AddressInfo] =
    Future.failed(RuntimeException("getAddressInfo not used"))
  def isTxConfirmed(h: String):  Future[Boolean] =
    Future.failed(RuntimeException("isTxConfirmed not used"))
  def getUtxos(a: String):       Future[Seq[BlockfrostUtxo]] =
    Future.failed(NotImplementedError("getUtxos not used"))
  def submitTx(c: Array[Byte]):  Future[String] =
    Future.failed(NotImplementedError("submitTx not used"))

class ScalusSettlerTest extends AnyFunSuite:

  private val req = PaymentRequirements(
    scheme      = PaymentScheme.CardanoExact(BigInt(2_000_000), None),
    network     = Network.CardanoPreprod,
    asset       = Assets.USDC_BASE,
    payTo       = "addr_test1receiver",
    resource    = "/api/x",
    description = "Scalus settler test",
  )

  private val payload = PaymentPayload(
    scheme        = req.scheme,
    network       = req.network,
    authorization = TransferAuthorization("addr_test1payer", req.payTo, BigInt(2_000_000), BigInt(0), BigInt(0), ""),
    signature     = "",
    cardanoProof  = Some(CardanoPaymentProof("addr_test1payer", "00", "00")),
  )

  // ── Stub settler ─────────────────────────────────────────────────────────────

  test("ScalusSettler.unimplemented: returns Fail with descriptive message") {
    val result = Await.result(ScalusSettler.unimplemented.submit(payload, req), 5.seconds)
    result match
      case SettleResult.Fail(msg) =>
        assert(msg.contains("not yet implemented"))
        assert(msg.contains("Phase 4") || msg.contains("Blockfrost"))
      case other => fail(s"expected Fail, got $other")
  }

  test("asConfigHook: produces a function that delegates to submit") {
    val hook = ScalusSettler.asConfigHook(ScalusSettler.unimplemented)
    val result = Await.result(hook(payload, req), 5.seconds)
    assert(result.isInstanceOf[SettleResult.Fail])
  }

  // ── Facilitator delegation ───────────────────────────────────────────────────

  test("CardanoFacilitator.settle (Scalus provider, no settler): Fail with hint") {
    val cfg = CardanoFacilitatorConfig(
      network         = CardanoNetwork.Preprod,
      provider        = CardanoProvider.Scalus("/tmp/socket"),
      receiverAddress = "addr_test1receiver",
    )
    val fac    = CardanoFacilitator(cfg, new MockBlockfrostBase {})
    val result = Await.result(fac.settle(payload, req), 5.seconds)
    result match
      case SettleResult.Fail(msg) =>
        assert(msg.contains("not yet implemented"))
        assert(msg.contains("scalusSettle") || msg.contains("Scalus"))
      case other => fail(s"expected Fail, got $other")
  }

  test("CardanoFacilitator.settle (Scalus provider, with settler): delegates") {
    val cfg = CardanoFacilitatorConfig(
      network         = CardanoNetwork.Preprod,
      provider        = CardanoProvider.Scalus("/tmp/socket"),
      receiverAddress = "addr_test1receiver",
      scalusSettle    = Some(ScalusSettler.asConfigHook(new ScalusSettler:
        def submit(p: PaymentPayload, r: PaymentRequirements): Future[SettleResult] =
          Future.successful(SettleResult.Ok("test-tx-hash-0"))
      )),
    )
    val fac    = CardanoFacilitator(cfg, new MockBlockfrostBase {})
    val result = Await.result(fac.settle(payload, req), 5.seconds)
    assert(result == SettleResult.Ok("test-tx-hash-0"))
  }

  test("CardanoFacilitator.settle (Blockfrost provider): unaffected — still optimistic Ok") {
    val cfg = CardanoFacilitatorConfig(
      network         = CardanoNetwork.Preprod,
      provider        = CardanoProvider.Blockfrost(scalascript.blockfrost.BlockfrostConfig("dummy")),
      receiverAddress = "addr_test1receiver",
    )
    val fac    = CardanoFacilitator(cfg, new MockBlockfrostBase {})
    val result = Await.result(fac.settle(payload, req), 5.seconds)
    assert(result.isInstanceOf[SettleResult.Ok])
  }
