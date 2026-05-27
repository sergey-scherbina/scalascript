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

/** SCT Inst 10-second transmission window exceeded (HTTP 408-style).
 *  EBA sanctions screening must complete within the 10s window; if the aggregator
 *  cannot confirm settlement in time it returns a timeout error.
 *  @param endToEndId the EndToEndId of the transfer that timed out
 *  @param elapsedMs  milliseconds elapsed before the timeout was declared */
case class SctInstTimeout(endToEndId: String, elapsedMs: Long)
    extends BankRailsError(s"SCT Inst 10-second window exceeded for $endToEndId (${elapsedMs}ms elapsed)")

/** UK FPS Confirmation of Payee name-check failed (result was NoMatch).
 *  `suggested` is the bank-registered name if CoP returned a CloseMatch. */
case class UkCopNameMismatch(suggested: Option[String])
    extends BankRailsError(
      suggested.fold("Confirmation of Payee name check returned NoMatch")(
        s => s"Confirmation of Payee name check returned CloseMatch — suggested name: $s"
      )
    )
