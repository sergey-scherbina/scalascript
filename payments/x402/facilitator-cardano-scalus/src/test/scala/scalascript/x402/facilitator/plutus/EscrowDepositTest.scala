package scalascript.x402.facilitator.plutus

import org.bouncycastle.crypto.digests.Blake2bDigest
import org.scalatest.funsuite.AnyFunSuite
import scalascript.blockfrost.{AddressInfo, BlockfrostClient, BlockfrostUtxo}
import scalascript.blockchain.cardano.CardanoAddress
import scalascript.x402.{Assets, Network, PaymentRequirements, PaymentScheme, ScalusClaimMessageCodec}

import scala.concurrent.{Await, Future}
import scala.concurrent.duration.*

class EscrowDepositTest extends AnyFunSuite:

  /** Fixed Ed25519 test private key (32 bytes hex).
   *  From CardanoPayloadTest — deterministic, well-known test fixture. */
  private val payerPrivKeyHex = "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60"

  /** Derive the public key bytes from the private key via BouncyCastle. */
  private val payerPublicKeyHex: String =
    import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
    val privBytes = payerPrivKeyHex.grouped(2).map(Integer.parseInt(_, 16).toByte).toArray
    val privKey   = Ed25519PrivateKeyParameters(privBytes, 0)
    val pubKey    = privKey.generatePublicKey()
    pubKey.getEncoded.map(b => f"${b & 0xff}%02x").mkString

  private val receiverAddress = EscrowScript.address(Network.CardanoPreprod)
  private val lovelace        = BigInt(3_000_000)

  private val req = PaymentRequirements(
    scheme      = PaymentScheme.CardanoExact(lovelace, None),
    network     = Network.CardanoPreprod,
    asset       = Assets.USDC_BASE,
    payTo       = receiverAddress,
    resource    = "/api/test",
    description = "deposit test",
  )

  private def mockCfg: ScalusSettlerConfig = ScalusSettlerConfig(
    network              = Network.CardanoPreprod,
    blockfrost           = new BlockfrostClient:
      def getAddressInfo(a: String): Future[AddressInfo] = Future.failed(RuntimeException("not used"))
      def isTxConfirmed(h: String): Future[Boolean]      = Future.failed(RuntimeException("not used"))
      def getUtxos(a: String): Future[Seq[BlockfrostUtxo]] = Future.failed(RuntimeException("not used"))
      def submitTx(c: Array[Byte]): Future[String]       = Future.failed(RuntimeException("not used")),
    relayerSigningKeyHex = "11" * 32,
    feeLovelace          = BigInt(170_000),
  )

  test("EscrowDeposit: datum fields are correct") {
    val validBeforeSlot = 99_000_000L
    val refundAfterSlot = 99_100_000L

    val plan = Await.result(
      EscrowDeposit.build(payerPublicKeyHex, req, validBeforeSlot, refundAfterSlot, mockCfg),
      5.seconds,
    )

    val datum = plan.datum

    // payerKeyHash: Blake2b-224 of raw public key bytes (28 bytes)
    val expectedPayerKeyHash = blake2b224(
      payerPublicKeyHex.grouped(2).map(Integer.parseInt(_, 16).toByte).toArray
    )
    assert(datum.payerKeyHash.toSeq == expectedPayerKeyHash.toSeq,
      "payerKeyHash must be Blake2b-224 of payer's public key")

    // receiverHash: first 28 bytes after the header byte in the bech32 payload
    val addrBytes           = CardanoAddress.toBytes(receiverAddress)
    val expectedReceiverHash = addrBytes.slice(1, 29)
    assert(datum.receiverHash.toSeq == expectedReceiverHash.toSeq,
      "receiverHash must be the payment credential bytes of the receiver address")

    // claimMessageHash: Blake2b-256 of the ScalusClaimMessage bytes
    val claimMsgBytes = ScalusClaimMessageCodec.encode(addrBytes, lovelace, BigInt(validBeforeSlot))
    val expectedMsgHash = blake2b256(claimMsgBytes)
    assert(datum.claimMessageHash.toSeq == expectedMsgHash.toSeq,
      "claimMessageHash must be Blake2b-256 of the claim message")

    assert(datum.amount      == lovelace)
    assert(datum.validBefore == BigInt(validBeforeSlot))
    assert(datum.refundAfter == BigInt(refundAfterSlot))
  }

  test("EscrowDeposit: lovelace output matches the payment amount") {
    val plan = Await.result(
      EscrowDeposit.build(payerPublicKeyHex, req, 99_000_000L, 99_100_000L, mockCfg),
      5.seconds,
    )
    assert(plan.lovelace == lovelace,
      s"DepositTxPlan.lovelace should equal req lovelace, got ${plan.lovelace}")

    // The transaction CBOR must be non-empty
    assert(plan.depositTxCbor.nonEmpty, "deposit CBOR should not be empty")
  }

  test("EscrowDeposit: output uses the escrow script address") {
    val plan = Await.result(
      EscrowDeposit.build(payerPublicKeyHex, req, 99_000_000L, 99_100_000L, mockCfg),
      5.seconds,
    )
    assert(plan.scriptAddress == EscrowScript.address(Network.CardanoPreprod),
      s"script address should match EscrowScript.address(Preprod), got ${plan.scriptAddress}")
  }

  // ── Helpers ───────────────────────────────────────────────────────────────────

  private def blake2b224(bytes: Array[Byte]): Array[Byte] =
    val digest = Blake2bDigest(224)
    digest.update(bytes, 0, bytes.length)
    val out = new Array[Byte](28)
    digest.doFinal(out, 0)
    out

  private def blake2b256(bytes: Array[Byte]): Array[Byte] =
    val digest = Blake2bDigest(256)
    digest.update(bytes, 0, bytes.length)
    val out = new Array[Byte](32)
    digest.doFinal(out, 0)
    out
