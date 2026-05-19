package scalascript.x402.facilitator

import scalascript.x402.*
import scalascript.evm.EvmClient
import scala.concurrent.{ExecutionContext, Future}

// ── EVM facilitator config ────────────────────────────────────────────────────

case class EvmFacilitatorConfig(
  evm:     EvmClient,
  // Optional: provide a settle backend for on-chain submission.
  // When absent, settle returns Ok without on-chain action (useful for testing).
  settler: Option[(PaymentPayload, PaymentRequirements) => Future[SettleResult]] = None,
)

// ── EVM facilitator ───────────────────────────────────────────────────────────
// Verify: check ERC-20 token balance >= required value + not expired.
// Settle: delegate to configured settler, or return testnet Ok if none provided.

class EvmFacilitatorImpl(config: EvmFacilitatorConfig)(using ec: ExecutionContext)
    extends Facilitator:

  def verify(payload: PaymentPayload, req: PaymentRequirements): Future[VerifyResult] =
    val auth  = payload.authorization
    val now   = BigInt(System.currentTimeMillis() / 1000)

    if auth.validBefore <= now then
      Future.successful(VerifyResult.Fail("Authorization expired"))
    else if auth.to.toLowerCase != req.payTo.toLowerCase then
      Future.successful(VerifyResult.Fail(s"Payment destination mismatch: ${auth.to} != ${req.payTo}"))
    else
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
