package scalascript.x402.facilitator.plutus

import scalascript.x402.{PaymentPayload, PaymentRequirements, SettleResult}
import scala.concurrent.Future

/** Pluggable settler for `CardanoProvider.Scalus`. Implementations build
 *  and submit a Plutus-escrow claim transaction that releases lovelace
 *  from the x402 escrow script address to the receiver, witnessed by the
 *  facilitator's relayer key.
 *
 *  The CIP-8 proof carried in `PaymentPayload.cardanoProof` is passed as
 *  the validator's redeemer; the on-chain script enforces signature
 *  correctness, message-hash equality with the datum, and exact-output
 *  shape (see [`docs/x402-cardano-scalus.md`](../../../../../../../../../docs/x402-cardano-scalus.md)
 *  §3.2).
 *
 *  Phase 1 ships only the trait and an `unimplemented` stub —
 *  bloxbean-backed implementations land in Phase 4. */
trait ScalusSettler:
  def submit(
    payload: PaymentPayload,
    req:     PaymentRequirements,
  ): Future[SettleResult]

object ScalusSettler:

  /** Trait-shape placeholder. Returns `Fail` with a clear "not yet
   *  implemented" message so callers that wire up a facilitator without
   *  picking a real settler get an explicit error instead of a silent
   *  Ok. Matches the historical hardcoded behavior in
   *  `CardanoFacilitator` pre-Phase-1. */
  val unimplemented: ScalusSettler = new ScalusSettler:
    def submit(
      payload: PaymentPayload,
      req:     PaymentRequirements,
    ): Future[SettleResult] =
      val _ = payload
      val _ = req
      Future.successful(SettleResult.Fail(
        "Scalus settlement not yet implemented — configure a real " +
          "ScalusSettler (Phase 4) or use CardanoProvider.Blockfrost"
      ))

  /** Adapt a `ScalusSettler` into the function shape consumed by
   *  `CardanoFacilitatorConfig.scalusSettle`. The base
   *  `x402-facilitator-cardano` module avoids a dependency on this one
   *  by accepting a plain `(payload, req) => Future[SettleResult]`. */
  def asConfigHook(settler: ScalusSettler): (PaymentPayload, PaymentRequirements) => Future[SettleResult] =
    settler.submit
