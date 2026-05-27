package scalascript.payments.bankrails

import scalascript.payments.money.Money

/** Base class for all bank rails errors. */
sealed class BankRailsError(msg: String) extends RuntimeException(msg)

case class UnsupportedRail(rail: RailKind, provider: String)
    extends BankRailsError(s"$provider does not support $rail")

case class TransferNotFound(id: TransferId)
    extends BankRailsError(s"transfer ${id.value} not found")

case class MandateNotActive(id: MandateId, status: MandateStatus)
    extends BankRailsError(s"mandate ${id.value} is $status, not Active")

case class DuplicateTransferRequest(originalId: TransferId)
    extends BankRailsError(s"idempotency key already used for transfer ${originalId.value}")

case class BankRailsCancelError(id: TransferId, reason: String)
    extends BankRailsError(s"cannot cancel ${id.value}: $reason")

case class FedNowLimitExceeded(amount: Money, limit: Money)
    extends BankRailsError(s"amount $amount exceeds FedNow limit $limit")

case class PixKeyNotFound(key: String)
    extends BankRailsError(s"Pix key not found: $key")

case class NachaCutoffMissed(scheduledDate: java.time.LocalDate)
    extends BankRailsError(s"ACH cut-off missed for $scheduledDate — next available date is next business day")

// ── v1.55 error additions ─────────────────────────────────────────────────────

// ── SWIFT (v1.55.1) ──────────────────────────────────────────────────────────

case class SwiftSanctionsHit(uetr: String, sanctionsRef: String)
    extends BankRailsError(s"SWIFT payment $uetr hit sanctions screening: $sanctionsRef")

case class SwiftUetrInvalid(uetr: String)
    extends BankRailsError(s"UETR is not a valid UUID v4: $uetr")

// ── SEPA Instant / SCT Inst (v1.55.2) ────────────────────────────────────────

/** SCT Inst 10-second transmission window exceeded (HTTP 408-style).
 *  EBA sanctions screening must complete within the 10s window; if the aggregator
 *  cannot confirm settlement in time it returns a timeout error.
 *  @param endToEndId the EndToEndId of the transfer that timed out
 *  @param elapsedMs  milliseconds elapsed before the timeout was declared */
case class SctInstTimeout(endToEndId: String, elapsedMs: Long)
    extends BankRailsError(s"SCT Inst 10-second window exceeded for $endToEndId (${elapsedMs}ms elapsed)")

// ── UK FPS (v1.55.3) ─────────────────────────────────────────────────────────

/** UK FPS Confirmation of Payee name-check failed (result was NoMatch).
 *  `suggested` is the bank-registered name if CoP returned a CloseMatch. */
case class UkCopNameMismatch(suggested: Option[String])
    extends BankRailsError(
      suggested.fold("Confirmation of Payee name check returned NoMatch")(
        s => s"Confirmation of Payee name check returned CloseMatch — suggested name: $s"
      )
    )

// ── UK BACS DD (v1.55.4) ─────────────────────────────────────────────────────

/** BACS 3-day submission cycle missed; the payment cannot be submitted until the next window.
 *  @param nextWindow the earliest date on which a new BACS submission can be made */
case class BacsCycleMissed(nextWindow: java.time.LocalDate)
    extends BankRailsError(s"BACS submission cycle missed — next available window: $nextWindow")

// ── India UPI (v1.55.6) ──────────────────────────────────────────────────────

/** UPI two-factor (UPI PIN entry on payer's device) timed out before the payer approved.
 *  The transaction is expired; a new collect request must be initiated.
 *  @param txnId the `txnId` of the collect request that expired */
case class UpiTwoFactorTimeout(txnId: String)
    extends BankRailsError(s"UPI two-factor PIN entry timed out for transaction $txnId")

// ── Japan Zengin (v1.55.7) ───────────────────────────────────────────────────

/** Japan Zengin transfer attempted outside the settlement window (08:30–15:30 JST).
 *  @param nextOpen the next datetime when the Zengin window opens */
case class ZenginOutsideWindow(nextOpen: java.time.ZonedDateTime)
    extends BankRailsError(s"Japan Zengin settlement window closed; next open: $nextOpen")

// ── Singapore PayNow (v1.55.8) ───────────────────────────────────────────────

/** PayNow proxy (mobile / NRIC / UEN) was not found in the PayNow proxy registry.
 *  @param proxyType  "MOBILE", "NRIC", or "UEN"
 *  @param proxyValue the proxy value that was not resolved */
case class PayNowProxyNotFound(proxyType: String, proxyValue: String)
    extends BankRailsError(s"PayNow proxy not found: $proxyType=$proxyValue")
