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

/** BACS Direct Debit submission missed the current cycle cut-off.
 *  `nextWindow` is the next available submission date (typically next business day). */
case class BacsCycleMissed(nextWindow: java.time.LocalDate)
    extends BankRailsError(s"BACS cycle cut-off missed; next available submission date: $nextWindow")

// ── India UPI (v1.55.6) ───────────────────────────────────────────────────────

/** UPI Collect two-factor approval timed out (payer did not respond on their device).
 *  `txnId` is the NPCI transaction ID that expired. */
case class UpiTwoFactorTimeout(txnId: String)
    extends BankRailsError(s"UPI two-factor approval timed out for transaction $txnId")

// ── Japan Zengin (v1.55.7) ────────────────────────────────────────────────────

/** Zengin transfer attempted outside the settlement window (08:30–15:30 JST, M–F).
 *  `nextOpen` is the next time the settlement window opens (JST). */
case class ZenginOutsideWindow(nextOpen: java.time.ZonedDateTime)
    extends BankRailsError(s"Zengin settlement window not open; next open: $nextOpen")

// ── Singapore PayNow (v1.55.8) ───────────────────────────────────────────────

/** PayNow proxy (mobile / NRIC / UEN / VPA) was not found in the PayNow proxy registry.
 *  Raised during proxy resolution before the payment is submitted to the FAST network.
 *  @param proxyType  "MOBILE", "NRIC", "UEN", or "VPA"
 *  @param proxyValue the proxy value that was not resolved */
case class PayNowProxyNotFound(proxyType: String, proxyValue: String)
    extends BankRailsError(s"PayNow proxy not found: $proxyType=$proxyValue")

// ── Australia NPP (v1.57.1) ───────────────────────────────────────────────────

/** NPP PayID (mobile / email / ABN / ACN / ORG) was not found in the PayID directory.
 *  Raised during PayID resolution before the payment is submitted to the Osko network.
 *  @param payid the PayID proxy value that was not resolved */
case class NppPayIdNotFound(payid: String)
    extends BankRailsError(s"AU NPP PayID not found: $payid")

// ── Canada Interac e-Transfer (v1.57.2) ───────────────────────────────────────

/** Interac e-Transfer recipient (email or phone) was not found in the Interac network.
 *  @param recipient the email address or phone number that could not be found */
case class InteracRecipientNotFound(recipient: String)
    extends BankRailsError(s"Interac e-Transfer recipient not found: $recipient")

// ── Mexico SPEI (v1.57.3) ────────────────────────────────────────────────────

/** CLABE (Clave Bancaria Estandarizada) control-digit validation failed.
 *  CLABE is an 18-digit number where the last digit is derived from the first 17.
 *  @param clabe  the CLABE that failed validation
 *  @param reason human-readable failure reason (wrong length, non-digits, bad check digit) */
case class ClabeValidationError(clabe: String, reason: String)
    extends BankRailsError(s"CLABE validation failed for '$clabe': $reason")
