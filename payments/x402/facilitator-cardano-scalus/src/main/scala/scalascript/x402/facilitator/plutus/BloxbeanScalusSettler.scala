package scalascript.x402.facilitator.plutus

import scalascript.blockfrost.BlockfrostClient
import scalascript.x402.*

import scala.concurrent.{ExecutionContext, Future}

case class ScalusSettlerConfig(
  network:              Network,
  blockfrost:           BlockfrostClient,
  relayerSigningKeyHex: String,
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
      yield ClaimTxPlan(
        network         = cfg.network,
        escrowRef       = ref,
        scriptAddress   = EscrowScript.address(cfg.network),
        receiverAddress = req.payTo,
        lovelace        = lovelace,
        coseSign1Hex    = proof.signature,
        coseKeyHex      = proof.key,
        relayerKeyHex   = cfg.relayerSigningKeyHex,
      )

object BloxbeanScalusSettler:
  def apply(
    cfg:     ScalusSettlerConfig,
    builder: ClaimTxBuilder = BloxbeanClaimTxBuilder.unimplemented,
  )(using ExecutionContext): BloxbeanScalusSettler =
    new BloxbeanScalusSettler(cfg, builder)
