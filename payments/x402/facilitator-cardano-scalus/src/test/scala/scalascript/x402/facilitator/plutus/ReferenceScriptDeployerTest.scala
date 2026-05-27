package scalascript.x402.facilitator.plutus

import org.scalatest.funsuite.AnyFunSuite
import scalascript.blockfrost.{AddressInfo, BlockfrostClient, BlockfrostUtxo}
import scalascript.x402.{Network, ScalusEscrowRef}

import scala.concurrent.{Await, Future}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.*

private final class MockDeployBlockfrost(returnedTxHash: String) extends BlockfrostClient:
  var submittedBytes: Option[Array[Byte]] = None

  def getAddressInfo(a: String): Future[AddressInfo] =
    Future.failed(RuntimeException("not used"))
  def isTxConfirmed(h: String): Future[Boolean] =
    Future.failed(RuntimeException("not used"))
  def getUtxos(a: String): Future[Seq[BlockfrostUtxo]] =
    Future.failed(RuntimeException("not used"))
  def submitTx(cbor: Array[Byte]): Future[String] =
    submittedBytes = Some(cbor)
    Future.successful(returnedTxHash)

class ReferenceScriptDeployerTest extends AnyFunSuite:

  private val signingKeyHex = "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60"

  test("deploy: submits the deploy tx and returns txHash + output index 0") {
    val expectedTxHash = "a" * 64
    val blockfrost     = MockDeployBlockfrost(expectedTxHash)

    val (txHash, index) = Await.result(
      ReferenceScriptDeployer.deploy(blockfrost, Network.CardanoPreprod, signingKeyHex),
      10.seconds,
    )

    assert(txHash == expectedTxHash)
    assert(index == 0)
    assert(blockfrost.submittedBytes.exists(_.nonEmpty),
      "deploy should have submitted CBOR bytes")
  }

  test("referenceScriptRef: round-trip parse of deploy result into ScalusSettlerConfig") {
    val blockfrost = MockDeployBlockfrost("b" * 64)
    val (txHash, index) = Await.result(
      ReferenceScriptDeployer.deploy(blockfrost, Network.CardanoPreprod, signingKeyHex),
      10.seconds,
    )

    val refString = s"$txHash#$index"
    val parsed    = ScalusEscrowRef.parse(refString)
    assert(parsed.isRight, s"referenceScriptRef should parse successfully: $parsed")
    val ref = parsed.getOrElse(throw RuntimeException("parse failed"))
    assert(ref.txHash == txHash)
    assert(ref.outputIndex == index)

    // Round-trip through ScalusSettlerConfig
    val cfg = ScalusSettlerConfig(
      network              = Network.CardanoPreprod,
      blockfrost           = blockfrost,
      relayerSigningKeyHex = signingKeyHex,
      referenceScriptRef   = Some(refString),
    )
    assert(cfg.referenceScriptRef.contains(refString))
    val reparsed = cfg.referenceScriptRef.flatMap(s => ScalusEscrowRef.parse(s).toOption)
    assert(reparsed.exists(_.txHash == txHash))
    assert(reparsed.exists(_.outputIndex == index))
  }
