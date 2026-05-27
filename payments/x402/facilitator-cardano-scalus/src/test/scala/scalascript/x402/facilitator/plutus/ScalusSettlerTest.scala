package scalascript.x402.facilitator.plutus

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

private final class SubmitBlockfrost(txHash: String) extends MockBlockfrostBase:
  var submitted: Vector[Array[Byte]] = Vector.empty
  override def getAddressInfo(a: String): Future[AddressInfo] =
    Future.failed(RuntimeException("getAddressInfo not used"))
  override def isTxConfirmed(h: String):  Future[Boolean] =
    Future.failed(RuntimeException("isTxConfirmed not used"))
  override def submitTx(c: Array[Byte]): Future[String] =
    submitted = submitted :+ c
    Future.successful(txHash)

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

  private val scalusPayload = payload.copy(
    authorization = payload.authorization.copy(nonce = "a" * 64 + "#1"),
    cardanoProof  = Some(CardanoPaymentProof("addr_test1payer", "c0ffee", "cafe")),
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

  // ── Bloxbean-backed settler pipeline ─────────────────────────────────────────

  test("ScalusSettler.preprod: validates network and submits builder CBOR through Blockfrost") {
    val blockfrost = SubmitBlockfrost("claim-tx-hash")
    var seenPlan: Option[ClaimTxPlan] = None
    val builder = new ClaimTxBuilder:
      def build(plan: ClaimTxPlan): Future[Array[Byte]] =
        seenPlan = Some(plan)
        Future.successful(Array[Byte](0x84.toByte, 0x00))

    val cfg = ScalusSettlerConfig(
      network              = Network.CardanoPreprod,
      blockfrost           = blockfrost,
      relayerSigningKeyHex = "11" * 32,
      collateralRef        = Some(ScalusEscrowRef("b" * 64, 0)),
      relayerKeyHashHex    = Some("42" * 28),
    )
    val settler = ScalusSettler.preprod(cfg, builder)
    val result  = Await.result(settler.submit(scalusPayload, req), 5.seconds)

    assert(result == SettleResult.Ok("claim-tx-hash"))
    assert(blockfrost.submitted.map(_.toSeq) == Vector(Seq[Byte](0x84.toByte, 0x00)))
    val plan = seenPlan.getOrElse(fail("builder was not called"))
    assert(plan.network == Network.CardanoPreprod)
    assert(plan.escrowRef == ScalusEscrowRef("a" * 64, 1))
    assert(plan.scriptAddress == EscrowScript.address(Network.CardanoPreprod))
    assert(plan.receiverAddress == req.payTo)
    assert(plan.lovelace == BigInt(2_000_000))
    assert(plan.coseSign1Hex == "c0ffee")
    assert(plan.coseKeyHex == "cafe")
    assert(plan.relayerKeyHex == "11" * 32)
    assert(plan.collateralRef.contains(ScalusEscrowRef("b" * 64, 0)))
    assert(plan.requiredSigner.exists(_.toSeq == Array.fill[Byte](28)(0x42.toByte).toSeq))
    assert(plan.claimRedeemer == EscrowRedeemerCodec.claim(CardanoPaymentProof("", "c0ffee", "cafe")))
  }

  test("BloxbeanScalusSettler: rejects malformed relayer key hash") {
    val cfg = ScalusSettlerConfig(
      network              = Network.CardanoPreprod,
      blockfrost           = SubmitBlockfrost("unused"),
      relayerSigningKeyHex = "11" * 32,
      relayerKeyHashHex    = Some("42" * 27),
    )
    val builder = new ClaimTxBuilder:
      def build(plan: ClaimTxPlan): Future[Array[Byte]] =
        fail("builder should not be called for invalid relayer key hash")
    val result = Await.result(ScalusSettler.preprod(cfg, builder).submit(scalusPayload, req), 5.seconds)
    result match
      case SettleResult.Fail(msg) => assert(msg.contains("relayerKeyHashHex"))
      case other                  => fail(s"expected Fail, got $other")
  }

  test("ScalusSettler.mainnet/preprod: reject mismatched configs") {
    val cfg = ScalusSettlerConfig(
      network              = Network.CardanoPreview,
      blockfrost           = SubmitBlockfrost("unused"),
      relayerSigningKeyHex = "11" * 32,
    )
    intercept[IllegalArgumentException](ScalusSettler.preprod(cfg))
    intercept[IllegalArgumentException](ScalusSettler.mainnet(cfg))
  }

  test("BloxbeanScalusSettler: rejects malformed escrowRef before builder") {
    val cfg = ScalusSettlerConfig(
      network              = Network.CardanoPreprod,
      blockfrost           = SubmitBlockfrost("unused"),
      relayerSigningKeyHex = "11" * 32,
    )
    val builder = new ClaimTxBuilder:
      def build(plan: ClaimTxPlan): Future[Array[Byte]] =
        fail("builder should not be called for invalid payload")
    val result = Await.result(
      ScalusSettler.preprod(cfg, builder).submit(payload, req),
      5.seconds,
    )
    result match
      case SettleResult.Fail(msg) => assert(msg.contains("escrowRef"))
      case other                  => fail(s"expected Fail, got $other")
  }

  test("BloxbeanScalusSettler: default builder fails explicitly until claim Tx construction lands") {
    val cfg = ScalusSettlerConfig(
      network              = Network.CardanoPreprod,
      blockfrost           = SubmitBlockfrost("unused"),
      relayerSigningKeyHex = "11" * 32,
    )
    val result = Await.result(ScalusSettler.preprod(cfg).submit(scalusPayload, req), 5.seconds)
    result match
      case SettleResult.Fail(msg) => assert(msg.contains("not implemented"))
      case other                  => fail(s"expected Fail, got $other")
  }
