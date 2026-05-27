package scalascript.x402.facilitator.plutus

import scalascript.blockfrost.BlockfrostClient
import scalascript.x402.*

import java.math.BigInteger
import java.util.Collections
import scala.concurrent.{ExecutionContext, Future}

case class ScalusSettlerConfig(
  network:              Network,
  blockfrost:           BlockfrostClient,
  relayerSigningKeyHex: String,
  collateralRef:        Option[ScalusEscrowRef] = None,
  relayerKeyHashHex:    Option[String]          = None,
)

case class ClaimTxPlan(
  network:         Network,
  escrowRef:       ScalusEscrowRef,
  scriptAddress:   String,
  receiverAddress: String,
  lovelace:        BigInt,
  coseSign1Hex:    String,
  coseKeyHex:      String,
  relayerKeyHex:   String,
  collateralRef:   Option[ScalusEscrowRef] = None,
  requiredSigner:  Option[Array[Byte]]     = None,
):
  lazy val claimRedeemer: com.bloxbean.cardano.client.plutus.spec.PlutusData =
    EscrowRedeemerCodec.claim(CardanoPaymentProof("", coseSign1Hex, coseKeyHex))

trait ClaimTxBuilder:
  def build(plan: ClaimTxPlan): Future[Array[Byte]]

object BloxbeanClaimTxBuilder:
  /** Placeholder until the Plutus witness / redeemer construction is
   *  implemented on top of cardano-client-lib. Keeping this as an
   *  injectable builder lets tests and future integration code exercise
   *  the settler pipeline without pretending the witness path is done. */
  val unimplemented: ClaimTxBuilder = (_: ClaimTxPlan) =>
    Future.failed(UnsupportedOperationException(
      "Bloxbean claim transaction construction is not implemented yet"
    ))

  /** Draft serializer for the deterministic script-input/output/redeemer
   *  skeleton. It is intentionally not the default production builder:
   *  fee balancing, collateral inputs, relayer vkey witness, and script
   *  data hash evaluation still have to be layered in before submission
   *  to a live network. */
  val draft: ClaimTxBuilder = BloxbeanClaimTxDraftBuilder

object BloxbeanClaimTxDraftBuilder extends ClaimTxBuilder:
  def build(plan: ClaimTxPlan): Future[Array[Byte]] =
    Future.successful(buildTransaction(plan).serialize())

  def buildTransaction(plan: ClaimTxPlan): com.bloxbean.cardano.client.transaction.spec.Transaction =
    import com.bloxbean.cardano.client.plutus.spec.*
    import com.bloxbean.cardano.client.spec.{Era, NetworkId}
    import com.bloxbean.cardano.client.transaction.spec.*

    val input = TransactionInput.builder()
      .transactionId(plan.escrowRef.txHash)
      .index(plan.escrowRef.outputIndex)
      .build()
    val output = TransactionOutput.builder()
      .address(plan.receiverAddress)
      .value(Value.fromCoin(bigInteger(plan.lovelace)))
      .build()
    val bodyBuilder = TransactionBody.builder()
      .inputs(Collections.singletonList(input))
      .outputs(Collections.singletonList(output))
      .fee(BigInteger.ZERO)
      .networkId(if plan.network == Network.CardanoMainnet then NetworkId.MAINNET else NetworkId.TESTNET)
    plan.collateralRef.foreach { ref =>
      bodyBuilder.collateral(Collections.singletonList(transactionInput(ref)))
    }
    plan.requiredSigner.foreach { keyHash =>
      bodyBuilder.requiredSigners(Collections.singletonList(keyHash))
    }
    val body = bodyBuilder.build()
    val script: PlutusV3Script = PlutusV3Script.builder()
      .cborHex(X402EscrowCompiled.doubleCborHex)
      .build()
      .asInstanceOf[PlutusV3Script]
    val redeemer = Redeemer.builder()
      .tag(RedeemerTag.Spend)
      .index(0)
      .data(plan.claimRedeemer)
      .exUnits(ExUnits.builder().mem(BigInteger.ZERO).steps(BigInteger.ZERO).build())
      .build()
    val witnesses = TransactionWitnessSet.builder()
      .plutusV3Scripts(Collections.singletonList(script))
      .redeemers(Collections.singletonList(redeemer))
      .build()
    Transaction.builder()
      .era(Era.Conway)
      .body(body)
      .witnessSet(witnesses)
      .isValid(true)
      .build()

  private def bigInteger(value: BigInt): BigInteger =
    new BigInteger(value.toString)

  private def transactionInput(ref: ScalusEscrowRef): com.bloxbean.cardano.client.transaction.spec.TransactionInput =
    import com.bloxbean.cardano.client.transaction.spec.TransactionInput
    TransactionInput.builder()
      .transactionId(ref.txHash)
      .index(ref.outputIndex)
      .build()

final class BloxbeanScalusSettler private (
  cfg:     ScalusSettlerConfig,
  builder: ClaimTxBuilder,
)(using ec: ExecutionContext) extends ScalusSettler:

  def submit(payload: PaymentPayload, req: PaymentRequirements): Future[SettleResult] =
    buildPlan(payload, req) match
      case Left(reason) => Future.successful(SettleResult.Fail(reason))
      case Right(plan) =>
        builder.build(plan)
          .flatMap(cfg.blockfrost.submitTx)
          .map(SettleResult.Ok(_))
          .recover { case ex => SettleResult.Fail(s"Scalus claim Tx failed: ${ex.getMessage}") }

  private def buildPlan(payload: PaymentPayload, req: PaymentRequirements): Either[String, ClaimTxPlan] =
    if payload.network != cfg.network then
      Left(s"Scalus settler network mismatch: payload=${payload.network}, settler=${cfg.network}")
    else if req.network != cfg.network then
      Left(s"Scalus settler request network mismatch: req=${req.network}, settler=${cfg.network}")
    else
      val lovelaceEither = req.scheme match
        case PaymentScheme.CardanoExact(amount, None) => Right(amount)
        case PaymentScheme.CardanoExact(_, Some(asset)) =>
          Left(s"Scalus settler currently supports lovelace-only payments, got native asset ${asset.symbol}")
        case other =>
          Left(s"Scalus settler only supports CardanoExact, got $other")

      for
        lovelace <- lovelaceEither
        proof    <- payload.cardanoProof.toRight("Missing Cardano payment proof")
        ref      <- ScalusEscrowRef.parse(payload.authorization.nonce)
        required <- parseOptionalRelayerKeyHash(cfg.relayerKeyHashHex)
      yield ClaimTxPlan(
        network         = cfg.network,
        escrowRef       = ref,
        scriptAddress   = EscrowScript.address(cfg.network),
        receiverAddress = req.payTo,
        lovelace        = lovelace,
        coseSign1Hex    = proof.signature,
        coseKeyHex      = proof.key,
        relayerKeyHex   = cfg.relayerSigningKeyHex,
        collateralRef   = cfg.collateralRef,
        requiredSigner  = required,
      )

object BloxbeanScalusSettler:
  def apply(
    cfg:     ScalusSettlerConfig,
    builder: ClaimTxBuilder = BloxbeanClaimTxBuilder.unimplemented,
  )(using ExecutionContext): BloxbeanScalusSettler =
    new BloxbeanScalusSettler(cfg, builder)

private def parseOptionalRelayerKeyHash(hex: Option[String]): Either[String, Option[Array[Byte]]] =
  hex match
    case None => Right(None)
    case Some(value) =>
      try
        val bytes = EscrowRedeemerCodec.hexToBytes(value)
        if bytes.length == 28 then Right(Some(bytes))
        else Left(s"relayerKeyHashHex must be 28 bytes (56 hex chars), got ${bytes.length} bytes")
      catch case ex: IllegalArgumentException =>
        Left(s"Invalid relayerKeyHashHex: ${ex.getMessage}")
