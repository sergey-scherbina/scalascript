package scalascript.x402.facilitator

import scalascript.x402.*
import scalascript.evm.EvmClient
import scalascript.blockchain.spi.ChainId
import scalascript.blockchain.evm.{Eip3009, EvmChainAdapter, Hex}
import scala.concurrent.{ExecutionContext, Future}

// ── EVM facilitator config ────────────────────────────────────────────────────

case class EvmFacilitatorConfig(
  evm:     EvmClient,
  // Optional: provide a settle backend for on-chain submission.
  // When absent, settle returns Ok without on-chain action (useful for testing).
  settler: Option[(PaymentPayload, PaymentRequirements) => Future[SettleResult]] = None,
)

// ── EVM facilitator ───────────────────────────────────────────────────────────
// Verify: signature recovery + token balance + expiry + payTo match.
// Settle: delegate to configured settler, or return testnet Ok if none provided.
//
// Signature verification was missing pre-Phase-1 (see docs/wallet-spi.md §9
// and docs/blockchain-spi.md §9.1) — a SHA-256 stub from x402-client would
// silently pass `verify` even though it could never settle on-chain. Now we
// reconstruct the canonical EIP-712 / EIP-3009 typed-data digest and call
// ecrecover via blockchain-evm before checking balance.

class EvmFacilitatorImpl(config: EvmFacilitatorConfig)(using ec: ExecutionContext)
    extends Facilitator:

  def verify(payload: PaymentPayload, req: PaymentRequirements): Future[VerifyResult] =
    val auth = payload.authorization
    val now  = BigInt(System.currentTimeMillis() / 1000)

    if auth.validBefore <= now then
      return Future.successful(VerifyResult.Fail("Authorization expired"))
    if auth.to.toLowerCase != req.payTo.toLowerCase then
      return Future.successful(
        VerifyResult.Fail(s"Payment destination mismatch: ${auth.to} != ${req.payTo}"),
      )

    // ── Signature recovery via EIP-3009 typed-data digest ──
    val recoveryResult: Either[String, Unit] =
      try
        val typedData = Eip3009.usdcTransferWithAuthorization(
          tokenAddress = req.asset.address,
          chainId      = req.network.chainId,
          from         = auth.from,
          to           = auth.to,
          value        = auth.value,
          validAfter   = auth.validAfter,
          validBefore  = auth.validBefore,
          nonceHex     = auth.nonce,
        )
        val adapter = new EvmChainAdapter(ChainId(s"eip155:${req.network.chainId}"))
        val digest  = adapter.typedDataDigest(typedData)
        val sig     = Hex.decode(payload.signature)
        adapter.recoverAddress(digest, sig) match
          case None =>
            Left("Signature recovery failed (malformed signature?)")
          case Some(recovered) if !recovered.equalsIgnoreCase(auth.from) =>
            Left(s"Signature mismatch: recovered $recovered, claimed ${auth.from}")
          case Some(_) =>
            Right(())
      catch
        case ex: Throwable => Left(s"Signature verification error: ${ex.getMessage}")

    recoveryResult match
      case Left(msg) =>
        Future.successful(VerifyResult.Fail(msg))
      case Right(_) =>
        config.evm.erc20Balance(req.asset.address, auth.from).map { balanceInt =>
          val balance = BigDecimal(balanceInt)
          if balance >= BigDecimal(auth.value) then VerifyResult.Ok
          else VerifyResult.Fail(s"Insufficient balance: $balanceInt < ${auth.value}")
        }.recover { case ex =>
          VerifyResult.Fail(s"EVM verify error: ${ex.getMessage}")
        }

  def settle(payload: PaymentPayload, req: PaymentRequirements): Future[SettleResult] =
    config.settler match
      case Some(settle) => settle(payload, req)
      case None         => Future.successful(SettleResult.Ok("0x" + "0" * 64))

object EvmFacilitator:
  def apply(config: EvmFacilitatorConfig)(using ExecutionContext): Facilitator =
    new EvmFacilitatorImpl(config)

  def apply(evm: EvmClient)(using ExecutionContext): Facilitator =
    new EvmFacilitatorImpl(EvmFacilitatorConfig(evm))

  def withSettler(
    evm:     EvmClient,
    settler: (PaymentPayload, PaymentRequirements) => Future[SettleResult],
  )(using ExecutionContext): Facilitator =
    new EvmFacilitatorImpl(EvmFacilitatorConfig(evm, Some(settler)))
