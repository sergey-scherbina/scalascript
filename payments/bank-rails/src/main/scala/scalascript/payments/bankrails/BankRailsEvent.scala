package scalascript.payments.bankrails

/** Typed webhook event union for all supported bank rails.
 *  See docs/bank-rails.md §7 for event taxonomy. */
enum BankRailsEvent:
  // ── SEPA Credit Transfer ─────────────────────────────────────────────────
  case SepaTransferCompleted(transfer: BankTransfer)
  case SepaTransferRejected(transfer: BankTransfer, code: RejectCode)
  case SepaTransferReturned(transfer: BankTransfer, code: ReturnCode)
  case SepaMandateActivated(mandate: DirectDebitMandate)
  case SepaMandateCanceled(mandate: DirectDebitMandate)
  case SepaDirectDebitCompleted(transfer: BankTransfer)
  case SepaDirectDebitReturned(transfer: BankTransfer, code: ReturnCode)

  // ── ACH ──────────────────────────────────────────────────────────────────
  case AchTransferSettled(transfer: BankTransfer)
  case AchReturn(transfer: BankTransfer, rCode: RCode, description: String)
  case AchNotificationOfChange(transfer: BankTransfer, cCode: CCode, correctedData: String)

  // ── Pix ──────────────────────────────────────────────────────────────────
  case PixReceived(transfer: BankTransfer)
  case PixRefunded(transfer: BankTransfer, original: TransferId)
  case PixRejected(transfer: BankTransfer, code: RejectCode)

  // ── FedNow ───────────────────────────────────────────────────────────────
  case FedNowCreditReceived(transfer: BankTransfer)
  case FedNowRejected(transfer: BankTransfer, code: RejectCode)

  // ── SWIFT GPI (v1.55.1) ──────────────────────────────────────────────────
  case SwiftMt103Booked(uetr: String, amount: String, currency: String)
  case SwiftPacs008Settled(uetr: String, amount: String, currency: String)
  case SwiftGpiAdvanced(uetr: String, hop: GpiHop)     // intermediate hop update
  case SwiftRejected(uetr: String, statusCode: String, reason: String)

  // ── SEPA Instant (SCT Inst) — v1.55.2 ───────────────────────────────────
  // pacs.002 ACCC from TIPS/RT1 acknowledgment via aggregator
  case SctInstSettled(endToEndId: String, amount: String, currency: String)
  // pacs.002 RJCT within the 10-second SCT Inst window
  case SctInstRejected(endToEndId: String, reason: String)

  // ── UK Faster Payments (v1.55.3) ─────────────────────────────────────────
  case UkFpsAccepted(txId: String, amount: String)
  case UkFpsRejected(txId: String, reason: String)
  case UkFpsReturned(txId: String, code: String, description: String)

  // ── UK BACS Direct Debit (v1.55.4) ───────────────────────────────────────
  // Aggregator confirms file accepted into BACS 3-day cycle
  case BacsDdSubmitted(ref: String, settlementDate: String)
  // Day 3 settlement confirmation — payment collected
  case BacsDdPaid(ref: String, amount: String)
  // New mandate accepted by payer's bank via AUDDIS
  case BacsAuddisAccepted(mandateRef: String)
  // Unpaid direct debit returned (ARUDD) — code is ARUDD/ADDACS return code
  case BacsAruddReturned(ref: String, code: String, description: String)

  // ── UK CHAPS (v1.55.5) ───────────────────────────────────────────────────
  // pacs.002 ACCC from BoE / aggregator: same-day RTGS settlement confirmed
  case ChapsSettled(endToEndId: String, amount: String, currency: String)
  // pacs.002 RJCT: payment rejected before or during RTGS queue processing
  case ChapsRejected(endToEndId: String, reason: String)

  // ── India UPI (v1.55.6) ──────────────────────────────────────────────────
  // UPI Collect request sent; payer has not yet approved
  case UpiCollectInitiated(txnId: String, vpa: String, amount: String)
  // Payer approves; final credit notification with UTR (Unique Transaction Reference)
  case UpiApproved(txnId: String, utrNumber: String)
  // Payer declines or UPI two-factor times out
  case UpiDeclined(txnId: String, reason: String)

  // ── Japan Zengin (v1.55.7) ────────────────────────────────────────────────
  case ZenginSettled(transferId: String, amount: String)
  case ZenginRejected(transferId: String, reason: String)

  // ── Singapore PayNow (v1.55.8) ────────────────────────────────────────────
  case PayNowSettled(txnRef: String, proxy: String, amount: String, currency: String)
  case PayNowFailed(txnRef: String, reason: String)
// ── ACH-specific return codes (Nacha R-codes) ────────────────────────────────

opaque type RCode = String
object RCode:
  val R01: RCode = "R01"  // insufficient funds
  val R02: RCode = "R02"  // bank account closed
  val R03: RCode = "R03"  // no bank account / unable to locate account
  val R04: RCode = "R04"  // invalid bank account number
  val R07: RCode = "R07"  // authorization revoked by customer
  val R08: RCode = "R08"  // payment stopped
  val R10: RCode = "R10"  // customer advises not authorized
  val R16: RCode = "R16"  // bank account frozen
  val R29: RCode = "R29"  // corporate customer advises not authorized
  val R61: RCode = "R61"  // misrouted return
  def apply(s: String): RCode = s
  extension (r: RCode)
    def value: String = r
    def description: String = r match
      case "R01" => "Insufficient Funds"
      case "R02" => "Account Closed"
      case "R03" => "No Account / Unable to Locate Account"
      case "R04" => "Invalid Account Number Structure"
      case "R07" => "Authorization Revoked by Customer"
      case "R08" => "Payment Stopped"
      case "R10" => "Customer Advises Not Authorized"
      case "R16" => "Account Frozen"
      case "R29" => "Corporate Customer Advises Not Authorized"
      case "R61" => "Misrouted Return"
      case other => s"Nacha return code $other"

// ── ACH-specific Notification of Change codes (Nacha C-codes) ────────────────

opaque type CCode = String
object CCode:
  val C01: CCode = "C01"  // incorrect bank account number
  val C02: CCode = "C02"  // incorrect routing number
  val C03: CCode = "C03"  // incorrect routing + account numbers
  val C05: CCode = "C05"  // incorrect transaction code
  val C06: CCode = "C06"  // incorrect account number and transaction code
  val C07: CCode = "C07"  // incorrect routing, account number and transaction code
  def apply(s: String): CCode = s
  extension (c: CCode) def value: String = c
