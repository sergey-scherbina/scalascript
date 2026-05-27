package scalascript.x402.facilitator.plutus

import org.bouncycastle.crypto.digests.Blake2bDigest
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.scalatest.funsuite.AnyFunSuite
import scalascript.blockfrost.{AddressInfo, BlockfrostClient, BlockfrostUtxo}
import scalascript.blockchain.cardano.CardanoAddress
import scalascript.x402.*
import scalascript.x402.facilitator.{
  CardanoFacilitator, CardanoFacilitatorConfig, CardanoNetwork, CardanoProvider,
}
import scalascript.x402.client.Cip8Signer

import scala.concurrent.{Await, Future}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.*

/** Full client → facilitator → settle plan round-trip using mock Blockfrost.
 *
 *  The test constructs a real Ed25519 signing key, builds a `ScalusClaimMessage`,
 *  signs it via CIP-8 directly (no dependency on `PayloadBuilder` which is
 *  package-private), then drives `CardanoFacilitator.verify` and `settle` with
 *  a `ScalusSettler.preprod` backed by a plan-capturing `ClaimTxBuilder`. */
class ScalusRoundTripTest extends AnyFunSuite:

  // ── Fixed key for deterministic tests ────────────────────────────────────────

  /** Known good Ed25519 key — same fixture as CardanoPayloadTest. */
  private val payerPrivKeyHex = "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60"

  /** Relayer key — any 32 bytes works for unit tests (key doesn't reach chain). */
  private val relayerKeyHex   = "11" * 32

  // ── Payment requirements ──────────────────────────────────────────────────────

  private val escrowRefStr    = "e" * 64 + "#2"
  private val lovelace        = BigInt(5_000_000)
  private val validBefore     = BigInt(9_999_999_999L)

  // Script address on preprod is the canonical receiver for escrow tests
  private val receiverAddress = EscrowScript.address(Network.CardanoPreprod)

  private val req = PaymentRequirements(
    scheme           = PaymentScheme.CardanoExact(lovelace, None),
    network          = Network.CardanoPreprod,
    asset            = Assets.USDC_BASE,
    payTo            = receiverAddress,
    resource         = "/api/scalus-round-trip",
    description      = "Scalus round-trip test",
    scalusEscrowRef  = Some(escrowRefStr),
  )

  // ── Mock Blockfrost ───────────────────────────────────────────────────────────

  private class MockSubmitBlockfrost extends BlockfrostClient:
    def getAddressInfo(a: String): Future[AddressInfo] =
      Future.failed(RuntimeException("not used"))
    def isTxConfirmed(h: String): Future[Boolean] =
      Future.failed(RuntimeException("not used"))
    def getUtxos(a: String): Future[Seq[BlockfrostUtxo]] =
      Future.failed(RuntimeException("not used"))
    def submitTx(c: Array[Byte]): Future[String] =
      Future.successful("round-trip-tx-hash-0")

  // ── Plan-capturing builder ────────────────────────────────────────────────────

  private class PlanCapturingBuilder extends ClaimTxBuilder:
    @volatile var capturedPlan: Option[ClaimTxPlan] = None
    def build(plan: ClaimTxPlan): Future[Array[Byte]] =
      capturedPlan = Some(plan)
      Future.successful(Array[Byte](0x84.toByte, 0x00))

  // ── Facilitator config ────────────────────────────────────────────────────────

  private def makeSettlerCfg(blockfrost: BlockfrostClient): ScalusSettlerConfig =
    ScalusSettlerConfig(
      network              = Network.CardanoPreprod,
      blockfrost           = blockfrost,
      relayerSigningKeyHex = relayerKeyHex,
      feeLovelace          = BigInt(170_000),
    )

  private def makeFacilitatorCfg(settler: ScalusSettler): CardanoFacilitatorConfig =
    CardanoFacilitatorConfig(
      network         = CardanoNetwork.Preprod,
      provider        = CardanoProvider.Scalus("/tmp/node.socket"),
      receiverAddress = receiverAddress,
      scalusSettle    = Some(ScalusSettler.asConfigHook(settler)),
    )

  // ── Helper: build a valid Scalus-mode payload manually ───────────────────────
  //
  // Replicates what `Wallets.cardano(..., scalusMode=true) + PayloadBuilder.build`
  // would do, without depending on the package-private `PayloadBuilder`.

  private def buildScalusPayload(): PaymentPayload =
    // Derive public key from private key
    val privBytes = hexToBytes(payerPrivKeyHex)
    val privKey   = Ed25519PrivateKeyParameters(privBytes, 0)
    val pubKey    = privKey.generatePublicKey()

    // Scalus claim message
    val recvBytes = CardanoAddress.toBytes(receiverAddress)
    val message   = ScalusClaimMessageCodec.encode(recvBytes, lovelace, validBefore)

    // CIP-8 sign
    val sigStructure = Cip8Signer.sigStructure(message)
    val signer = Ed25519Signer()
    signer.init(true, privKey)
    signer.update(sigStructure, 0, sigStructure.length)
    val signature = signer.generateSignature()

    // Derive bech32 payer address — same Blake2b-224 + Bech32 encoding as
    // CardanoAddress.fromPublicKey but done inline to avoid a dep on crypto.PublicKey
    val keyHash  = blake2b224(pubKey.getEncoded)
    val addrBytes = (0x70.toByte) +: keyHash
    val payerAddr = scalascript.blockchain.cardano.Bech32.encode("addr_test", addrBytes)

    val proof = Cip8Signer.buildProof(
      message   = message,
      signature = signature,
      publicKey = pubKey.getEncoded,
      address   = payerAddr,
    )

    val auth = TransferAuthorization(
      from        = proof.address,
      to          = receiverAddress,
      value       = lovelace,
      validAfter  = BigInt(0),
      validBefore = validBefore,
      nonce       = escrowRefStr,
    )
    PaymentPayload(
      scheme        = req.scheme,
      network       = req.network,
      authorization = auth,
      signature     = "",
      cardanoProof  = Some(proof),
    )

  // ── Tests ─────────────────────────────────────────────────────────────────────

  test("round-trip: Scalus-mode client → verify returns Ok") {
    val blockfrost = MockSubmitBlockfrost()
    val builder    = PlanCapturingBuilder()
    val settler    = ScalusSettler.preprod(makeSettlerCfg(blockfrost), builder)
    val facCfg     = makeFacilitatorCfg(settler)
    val fac        = CardanoFacilitator(facCfg, blockfrost)

    val payload = buildScalusPayload()
    val result  = Await.result(fac.verify(payload, req), 5.seconds)
    assert(result == VerifyResult.Ok,
      s"verify should return Ok for a valid Scalus-mode payload, got: $result")
  }

  test("round-trip: settle constructs a ClaimTxPlan without submitting to chain") {
    val blockfrost = MockSubmitBlockfrost()
    val builder    = PlanCapturingBuilder()
    val settler    = ScalusSettler.preprod(makeSettlerCfg(blockfrost), builder)
    val facCfg     = makeFacilitatorCfg(settler)
    val fac        = CardanoFacilitator(facCfg, blockfrost)

    val payload      = buildScalusPayload()
    val settleResult = Await.result(fac.settle(payload, req), 5.seconds)

    assert(settleResult == SettleResult.Ok("round-trip-tx-hash-0"),
      s"settle should return Ok with txHash, got: $settleResult")

    val plan = builder.capturedPlan.getOrElse(fail("builder was not called by settler"))
    assert(plan.network         == Network.CardanoPreprod)
    assert(plan.lovelace        == lovelace)
    assert(plan.receiverAddress == receiverAddress)
    assert(plan.escrowRef       == ScalusEscrowRef("e" * 64, 2))
    assert(plan.relayerKeyHex   == relayerKeyHex)
    assert(plan.coseSign1Hex.nonEmpty)
    assert(plan.coseKeyHex.nonEmpty)
  }

  test("round-trip: tampered COSE signature → verify returns Fail") {
    val blockfrost = MockSubmitBlockfrost()
    val builder    = PlanCapturingBuilder()
    val settler    = ScalusSettler.preprod(makeSettlerCfg(blockfrost), builder)
    val facCfg     = makeFacilitatorCfg(settler)
    val fac        = CardanoFacilitator(facCfg, blockfrost)

    val payload  = buildScalusPayload()
    val proof    = payload.cardanoProof.getOrElse(fail("no proof"))
    // Corrupt the last 4 bytes of the COSE_Sign1 hex
    val tampered   = proof.signature.dropRight(8) + "deadbeef"
    val badPayload = payload.copy(cardanoProof = Some(proof.copy(signature = tampered)))

    val result = Await.result(fac.verify(badPayload, req), 5.seconds)
    result match
      case VerifyResult.Fail(msg) =>
        assert(msg.contains("CIP-8") || msg.contains("signature") || msg.contains("Invalid"),
          s"Fail reason should reference signature problem, got: '$msg'")
      case VerifyResult.Ok =>
        fail("tampered signature should not verify as Ok")
  }

  test("round-trip: malformed escrowRef → verify returns Fail") {
    val blockfrost = MockSubmitBlockfrost()
    val builder    = PlanCapturingBuilder()
    val settler    = ScalusSettler.preprod(makeSettlerCfg(blockfrost), builder)
    val fac        = CardanoFacilitator(makeFacilitatorCfg(settler), blockfrost)

    val payload    = buildScalusPayload()
    val badPayload = payload.copy(
      authorization = payload.authorization.copy(nonce = "not-a-valid-ref"),
    )
    val badReq = req.copy(scalusEscrowRef = Some("not-a-valid-ref"))

    val result = Await.result(fac.verify(badPayload, badReq), 5.seconds)
    result match
      case VerifyResult.Fail(msg) =>
        assert(msg.contains("escrowRef") || msg.contains("Invalid Cardano"),
          s"Fail reason should reference escrowRef, got: '$msg'")
      case VerifyResult.Ok =>
        fail("malformed escrowRef should not verify as Ok")
  }

  // ── Helpers ───────────────────────────────────────────────────────────────────

  private def hexToBytes(hex: String): Array[Byte] =
    hex.grouped(2).map(Integer.parseInt(_, 16).toByte).toArray

  private def blake2b224(bytes: Array[Byte]): Array[Byte] =
    val digest = Blake2bDigest(224)
    digest.update(bytes, 0, bytes.length)
    val out = new Array[Byte](28)
    digest.doFinal(out, 0)
    out
