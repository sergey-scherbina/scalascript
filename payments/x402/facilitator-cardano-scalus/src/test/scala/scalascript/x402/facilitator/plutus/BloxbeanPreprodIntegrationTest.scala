package scalascript.x402.facilitator.plutus

import com.bloxbean.cardano.client.transaction.spec.Transaction
import org.scalatest.funsuite.AnyFunSuite
import scalascript.blockfrost.{Blockfrost, BlockfrostConfig}
import scalascript.x402.{Network, ScalusEscrowRef}

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.*

class BloxbeanPreprodIntegrationTest extends AnyFunSuite:

  test("preprod: build balanced claim draft from live Blockfrost protocol params") {
    assume(envFlag("X402_SCALUS_PREPROD_IT"), "set X402_SCALUS_PREPROD_IT=true to run")

    val blockfrostKey = env("X402_CARDANO_BLOCKFROST_KEY")
      .orElse(env("BLOCKFROST_KEY"))
      .getOrElse(cancel("set X402_CARDANO_BLOCKFROST_KEY or BLOCKFROST_KEY"))
    val receiver = env("X402_SCALUS_RECEIVER_ADDR").getOrElse(cancel("set X402_SCALUS_RECEIVER_ADDR"))
    val escrowRef = ScalusEscrowRef.parse(env("X402_SCALUS_ESCROW_REF").getOrElse(cancel("set X402_SCALUS_ESCROW_REF"))) match
      case Right(ref) => ref
      case Left(err)  => cancel(err)
    val relayerSKey = env("X402_SCALUS_RELAYER_SKEY_HEX").getOrElse(cancel("set X402_SCALUS_RELAYER_SKEY_HEX"))
    val collateralRef = env("X402_SCALUS_COLLATERAL_REF").map { raw =>
      ScalusEscrowRef.parse(raw) match
        case Right(ref) => ref
        case Left(err)  => cancel(err)
    }
    val requiredSigner = env("X402_SCALUS_RELAYER_KEY_HASH_HEX").map(EscrowRedeemerCodec.hexToBytes)

    val client = Blockfrost.connect(BlockfrostConfig(
      projectId = blockfrostKey,
      baseUrl   = "https://cardano-preprod.blockfrost.io/api/v0",
    ))
    val cfg = ScalusSettlerConfig(
      network              = Network.CardanoPreprod,
      blockfrost           = client,
      relayerSigningKeyHex = relayerSKey,
      collateralRef        = collateralRef,
      relayerKeyHashHex    = env("X402_SCALUS_RELAYER_KEY_HASH_HEX"),
      claimExUnits         = ScalusExUnits(
        mem   = env("X402_SCALUS_EX_MEM").map(BigInt(_)).getOrElse(BigInt(0)),
        steps = env("X402_SCALUS_EX_STEPS").map(BigInt(_)).getOrElse(BigInt(0)),
      ),
    )
    val plan = ClaimTxPlan(
      network         = Network.CardanoPreprod,
      escrowRef       = escrowRef,
      scriptAddress   = EscrowScript.address(Network.CardanoPreprod),
      receiverAddress = receiver,
      lovelace        = env("X402_SCALUS_LOVELACE").map(BigInt(_)).getOrElse(BigInt(2_000_000)),
      coseSign1Hex    = env("X402_SCALUS_COSE_SIGN1_HEX").getOrElse("c0ffee"),
      coseKeyHex      = env("X402_SCALUS_COSE_KEY_HEX").getOrElse("cafe"),
      relayerKeyHex   = relayerSKey,
      collateralRef   = collateralRef,
      requiredSigner  = requiredSigner,
      ttlSlot         = env("X402_SCALUS_TTL_SLOT").map(_.toLong),
      validityStart   = env("X402_SCALUS_VALIDITY_START_SLOT").map(_.toLong),
      claimExUnits    = cfg.claimExUnits,
    )

    val builder = BloxbeanClaimTxBuilder.draftBalancedFromBlockfrost(cfg)
    val cbor = Await.result(builder.build(plan), 30.seconds)
    val tx = Transaction.deserialize(cbor)
    assert(cbor.nonEmpty)
    assert(tx.getBody.getFee.signum() > 0)
    assert(tx.getBody.getNetworkId != null)

    if envFlag("X402_SCALUS_PREPROD_SUBMIT") then
      val txHash = Await.result(client.submitTx(cbor), 60.seconds)
      assert(txHash.nonEmpty)
  }

  private def env(name: String): Option[String] =
    sys.env.get(name).filter(_.nonEmpty)

  private def envFlag(name: String): Boolean =
    env(name).exists(v => v.equalsIgnoreCase("true") || v == "1" || v.equalsIgnoreCase("yes"))
