package scalascript.payments.bankrails

import scalascript.payments.money.Money

/** Opaque identifier for a direct-debit mandate. */
opaque type MandateId = String
object MandateId:
  def apply(s: String): MandateId = s
  extension (id: MandateId) def value: String = id

/** Request to initiate a pull (direct debit) collection against an existing mandate. */
case class InitiateDirectDebitRequest(
  rail:            RailKind,               // must be SEPA_DD or ACH_DEBIT
  amount:          Money,
  mandateId:       MandateId,
  creditorAccount: BankAccount,
  debtorAccount:   BankAccount,
  creditorName:    String,
  reference:       String,                 // per-collection reference (max 35 chars for SEPA)
  idempotencyKey:  String,
  sameDay:         Boolean = false,        // ACH only
  scheduledDate:   Option[java.time.LocalDate] = None,
  metadata:        Map[String, String] = Map.empty,
)

/** A direct-debit mandate — customer authorization for merchant to pull funds. */
case class DirectDebitMandate(
  id:              MandateId,
  rail:            RailKind,
  debtorAccount:   BankAccount,
  creditorAccount: BankAccount,
  creditorName:    String,
  status:          MandateStatus,
  signedAt:        Option[java.time.Instant] = None,
  maxAmount:       Option[Money] = None,          // SEPA: optional cap; ACH: rarely enforced
  sequenceType:    MandateSequenceType,
  providerRef:     Option[String] = None,         // PSP / aggregator mandate reference
  metadata:        Map[String, String] = Map.empty,
)

/** Lifecycle status of a direct-debit mandate. */
enum MandateStatus:
  case Pending    // created locally, not yet confirmed by bank
  case Active     // confirmed — debits may be initiated
  case Suspended  // temporarily blocked (e.g. pre-notification failed)
  case Canceled   // canceled by customer or merchant
  case Expired    // SEPA: 36-month inactivity; ACH: lender-specific
  case Revoked    // customer contacted their bank directly (ACH Reg E)

/** Sequence type for SEPA Direct Debit mandates.
 *  Maps to PAIN.008 SeqTp element: FRST/RCUR/FNAL/OOFF. */
enum MandateSequenceType:
  case OneOff     // single-use (OOFF)
  case First      // first in a series — SEPA-specific pre-notification (FRST)
  case Recurring  // subsequent recurring collection (RCUR)
  case Final      // last in a series (FNAL)
