package scalascript.payments.bankrails

import scalascript.payments.money.Money

/** Base class for all bank-rails errors. */
sealed class BankRailsError(msg: String) extends RuntimeException(msg)

case class UnsupportedRail(rail: RailKind, provider: String)
    extends BankRailsError(s"$provider does not support $rail")

case class TransferNotFound(id: TransferId)
    extends BankRailsError(s"transfer $id not found")

case class MandateNotActive(id: MandateId, status: MandateStatus)
    extends BankRailsError(s"mandate $id is $status, not Active")

case class DuplicateTransferRequest(originalId: TransferId)
    extends BankRailsError(s"idempotency key already used for transfer $originalId")

case class BankRailsCancelError(id: TransferId, reason: String)
    extends BankRailsError(s"cannot cancel $id: $reason")

case class FedNowLimitExceeded(amount: Money, limit: Money)
    extends BankRailsError(s"amount $amount exceeds FedNow limit $limit")

case class PixKeyNotFound(key: String)
    extends BankRailsError(s"Pix key not found: $key")

case class NachaCutoffMissed(scheduledDate: java.time.LocalDate)
    extends BankRailsError(s"ACH cut-off missed for $scheduledDate — next available date is next business day")

case class SepaMandateMissedDeadline(scheduledDate: java.time.LocalDate, earliestValid: java.time.LocalDate)
    extends BankRailsError(s"SEPA D-2 deadline missed for $scheduledDate; earliest valid date: $earliestValid")
