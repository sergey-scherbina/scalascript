package scalascript.x402.facilitator

import scalascript.x402.*
import scalascript.blockchain.cardano.CardanoAddress
import scalascript.blockfrost.{BlockfrostClient, BlockfrostConfig, Blockfrost}
import scala.concurrent.{ExecutionContext, Future}

// ── Config types ──────────────────────────────────────────────────────────────

enum CardanoNetwork:
  case Mainnet, Preprod, Preview

enum CardanoProvider:
  case Blockfrost(config: BlockfrostConfig)
  case Scalus(
    nodeSocket: String,
    ogmiosUrl:  Option[String] = None,
    kupoUrl:    Option[String] = None,
    signingKey: Option[String] = None,
  )

case class CardanoFacilitatorConfig(
  network:         CardanoNetwork,
  provider:        CardanoProvider,
  receiverAddress: String,
  /** Pluggable settler for `CardanoProvider.Scalus`. The Plutus-escrow
   *  claim-Tx construction lives in `x402-facilitator-cardano-scalus`
   *  and is injected here as a function so the base module has no
   *  hard dependency on bloxbean / Scalus.
   *
   *  See [`docs/x402-cardano-scalus.md`](../../../../../../../../docs/x402-cardano-scalus.md). */
  scalusSettle: Option[(PaymentPayload, PaymentRequirements) => Future[SettleResult]] = None,
)

// ── CIP-8 COSE_Sign1 verifier ─────────────────────────────────────────────────

object Cip8Verifier:
  import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
  import org.bouncycastle.crypto.signers.Ed25519Signer

  def verify(proof: CardanoPaymentProof, message: Array[Byte]): Boolean =
    try doVerify(proof, message)
    catch case _: Exception => false

  private def doVerify(proof: CardanoPaymentProof, message: Array[Byte]): Boolean =
    // 1. Decode COSE_Sign1 (tag 18 optional)
    val coseBytes = hexToBytes(proof.signature)
    val coseVal   = MiniCbor.decode(coseBytes) match
      case MiniCbor.Tagged(18, inner) => inner
      case v                           => v

    val (protBytes, payBytes, sigBytes) = coseVal match
      case MiniCbor.Arr(items) if items.length >= 4 =>
        val prot = items(0) match { case MiniCbor.Bytes(b) => b; case _ => return false }
        val pay  = items(2) match { case MiniCbor.Bytes(b) => b; case _ => return false }
        val sig  = items(3) match { case MiniCbor.Bytes(b) => b; case _ => return false }
        (prot, pay, sig)
      case _ => return false

    // 2. Payload must match the expected message
    if !java.util.Arrays.equals(payBytes, message) then return false

    // 3. Reconstruct Sig_Structure = ["Signature1", prot_bstr, h"", payload]
    val sigStructure = MiniCbor.encode(MiniCbor.Arr(IndexedSeq(
      MiniCbor.Text("Signature1"),
      MiniCbor.Bytes(protBytes),
      MiniCbor.Bytes(Array.empty),
      MiniCbor.Bytes(payBytes),
    )))

    // 4. Extract Ed25519 public key from COSE_Key (key -2 = NInt(1))
    val pubKeyBytes = extractPubKey(MiniCbor.decode(hexToBytes(proof.key)))
    if pubKeyBytes.isEmpty then return false

    // 5. Verify Ed25519 signature
    val pubKey   = Ed25519PublicKeyParameters(pubKeyBytes.get)
    val verifier = Ed25519Signer()
    verifier.init(false, pubKey)
    verifier.update(sigStructure, 0, sigStructure.length)
    verifier.verifySignature(sigBytes)

  private def extractPubKey(v: MiniCbor.Value): Option[Array[Byte]] =
    v match
      case MiniCbor.Map(entries) =>
        // CBOR integer -2 is encoded as NInt(1) since -(1+1) = -2
        entries.collectFirst { case (MiniCbor.NInt(1), MiniCbor.Bytes(b)) => b }
      case MiniCbor.Tagged(_, inner) => extractPubKey(inner)
      case _ => None

  private[facilitator] def hexToBytes(hex: String): Array[Byte] =
    val h = if hex.startsWith("0x") then hex.drop(2) else hex
    h.grouped(2).map(Integer.parseInt(_, 16).toByte).toArray

// ── Facilitator implementation ────────────────────────────────────────────────

private class CardanoFacilitatorImpl(
  config: CardanoFacilitatorConfig,
  client: BlockfrostClient,
)(using ec: ExecutionContext) extends Facilitator:

  def verify(payload: PaymentPayload, req: PaymentRequirements): Future[VerifyResult] =
    req.scheme match
      case scheme: PaymentScheme.CardanoExact =>
        payload.cardanoProof match
          case None =>
            Future.successful(VerifyResult.Fail("Missing Cardano payment proof"))
          case Some(proof) =>
            requirementsMessage(payload, req, scheme) match
              case Left(reason) =>
                Future.successful(VerifyResult.Fail(reason))
              case Right(msgBytes) if !Cip8Verifier.verify(proof, msgBytes) =>
                Future.successful(VerifyResult.Fail("Invalid CIP-8 signature"))
              case Right(_) =>
                config.provider match
                  case CardanoProvider.Scalus(_, _, _, _) =>
                    Future.successful(VerifyResult.Ok)
                  case CardanoProvider.Blockfrost(_) =>
                    checkBalance(proof.address, scheme)
      case _ =>
        Future.successful(VerifyResult.Fail("CardanoFacilitator only supports CardanoExact scheme"))

  def settle(payload: PaymentPayload, req: PaymentRequirements): Future[SettleResult] =
    config.provider match
      case CardanoProvider.Blockfrost(_) =>
        // Client-submitted model: balance was verified in verify(); return optimistic Ok
        Future.successful(SettleResult.Ok("0x" + "0" * 64))
      case CardanoProvider.Scalus(_, _, _, _) =>
        config.scalusSettle match
          case Some(settler) => settler(payload, req)
          case None          => Future.successful(SettleResult.Fail(
            "Scalus settlement not yet implemented — wire " +
              "CardanoFacilitatorConfig.scalusSettle (see x402-facilitator-cardano-scalus)"
          ))

  private def checkBalance(
    address: String,
    scheme:  PaymentScheme.CardanoExact,
  ): Future[VerifyResult] =
    client.getAddressInfo(address).map { info =>
      scheme.asset match
        case None =>
          if info.lovelaceBalance >= scheme.lovelace then VerifyResult.Ok
          else VerifyResult.Fail(
            s"Insufficient ADA: ${info.lovelaceBalance} lovelace, need ${scheme.lovelace}")
        case Some(asset) =>
          val unit   = asset.policyId + asset.assetName
          val actual = info.assets.getOrElse(unit, BigInt(0))
          if actual >= scheme.lovelace then VerifyResult.Ok
          else VerifyResult.Fail(
            s"Insufficient ${asset.symbol}: $actual, need ${scheme.lovelace}")
    }.recover { case ex =>
      VerifyResult.Fail(s"Balance check failed: ${ex.getMessage}")
    }

  private def requirementsMessage(
    payload: PaymentPayload,
    req:     PaymentRequirements,
    scheme:  PaymentScheme.CardanoExact,
  ): Either[String, Array[Byte]] =
    config.provider match
      case CardanoProvider.Blockfrost(_) =>
        // Legacy optimistic path: canonical message is UTF-8 bytes of
        // the description field, matching x402 Cardano clients.
        Right(req.description.getBytes("UTF-8"))
      case CardanoProvider.Scalus(_, _, _, _) =>
        if scheme.asset.nonEmpty then
          Left("Scalus settlement currently supports lovelace-only CardanoExact payments")
        else if payload.authorization.nonce.trim.isEmpty then
          Left("Missing Scalus escrowRef in authorization.nonce")
        else
          try Right(scalusClaimMessage(req.payTo, scheme.lovelace, payload.authorization.validBefore))
          catch case ex: IllegalArgumentException =>
            Left(s"Invalid Scalus claim message: ${ex.getMessage}")

  private def scalusClaimMessage(receiver: String, lovelace: BigInt, validBefore: BigInt): Array[Byte] =
    ScalusClaimMessageCodec.encode(CardanoAddress.toBytes(receiver), lovelace, validBefore)

// ── Factory ───────────────────────────────────────────────────────────────────

object CardanoFacilitator:
  def blockfrost(config: CardanoFacilitatorConfig)(using ExecutionContext): Facilitator =
    val bfCfg = config.provider match
      case CardanoProvider.Blockfrost(c) => c
      case _ => throw IllegalArgumentException("Provider must be Blockfrost")
    new CardanoFacilitatorImpl(config, Blockfrost.connect(bfCfg))

  def apply(
    config:  CardanoFacilitatorConfig,
    client:  BlockfrostClient,
  )(using ExecutionContext): Facilitator =
    new CardanoFacilitatorImpl(config, client)
