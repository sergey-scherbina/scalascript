package scalascript.payments.bankrails

import scalascript.payments.money.Money

/** Opaque identifier for a bank transfer. */
opaque type TransferId = String
object TransferId:
  def apply(s: String): TransferId = s
  extension (id: TransferId) def value: String = id

/** Bank account details. Fields used depend on the rail (IBAN for SEPA,
 *  routingNumber+accountNumber for ACH, pixKey for Pix, sortCode+accountNumber for UK FPS/BACS,
 *  bic for SWIFT). All v1.55 fields default to None — existing call sites compile unchanged. */
case class BankAccount(
  iban:             Option[String] = None,    // SEPA / SWIFT
  accountNumber:    Option[String] = None,    // ACH / Pix / FedNow / UK FPS / UK BACS
  routingNumber:    Option[String] = None,    // ACH (ABA routing number)
  bankCode:         Option[String] = None,    // Pix ISPB / FedNow routing
  pixKey:           Option[String] = None,    // Pix key (CPF/CNPJ/phone/email/EVP)
  holderName:       String,
  countryCode:      String,                   // ISO 3166-1 alpha-2
  // v1.55 additions — all default to None, no existing call sites break
  bic:              Option[String] = None,    // SWIFT/SCT Inst: BIC8 or BIC11
  sortCode:         Option[String] = None,    // UK FPS / BACS: 6-digit sort code "XX-XX-XX"
  upiVpa:           Option[String] = None,    // India UPI: Virtual Payment Address (name@bank)
  zenginBankCode:   Option[String] = None,    // Japan Zengin: 4-digit bank code
  zenginBranchCode: Option[String] = None,    // Japan Zengin: 3-digit branch code
  paynowProxy:      Option[String] = None,    // SG PayNow: mobile / NRIC / UEN proxy
  // v1.57 additions — all default to None, no existing call sites break
  payid:            Option[String] = None,    // AU NPP: PayID proxy (mobile/email/ABN)
  transitNumber:    Option[String] = None,    // Canada EFT: 5-digit transit/branch number
  institutionNumber: Option[String] = None,   // Canada EFT: 3-digit institution (bank) number
  clabe:            Option[String] = None,    // Mexico SPEI: 18-digit CLABE account number
)

/** Request to initiate a push bank transfer (credit transfer, Pix, FedNow). */
case class InitiateTransferRequest(
  rail:           RailKind,
  amount:         Money,
  sender:         BankAccount,
  recipient:      BankAccount,
  reference:      String,                // end-to-end reference (max 35 chars for SEPA)
  idempotencyKey: String,   // end-to-end idempotency key (used as EndToEndId in PAIN XML, txid for Pix)
  sameDay:        Boolean = false,       // ACH same-day flag; ignored for non-ACH rails
  scheduledDate:  Option[java.time.LocalDate] = None,  // None = earliest possible
  metadata:       Map[String, String] = Map.empty,
  // v1.55 SWIFT additions
  chargeBearer:   ChargeBearer = ChargeBearer.SHA,   // SWIFT only; ignored for other rails
  uetr:           Option[Uetr] = None,               // None = adapter generates a new UUID v4
)

/** Result of a bank transfer, carrying its lifecycle status. */
case class BankTransfer(
  id:          TransferId,
  rail:        RailKind,
  amount:      Money,
  sender:      BankAccount,
  recipient:   BankAccount,
  reference:   String,
  status:      BankTransferStatus,
  createdAt:   java.time.Instant,
  settledAt:   Option[java.time.Instant] = None,
  returnedAt:  Option[java.time.Instant] = None,
  metadata:    Map[String, String] = Map.empty,
  // v1.55 SWIFT additions
  uetr:         Option[Uetr] = None,             // SWIFT UETR (UUID v4); None for non-SWIFT rails
  gpiTrail:     List[GpiHop] = Nil,             // GPI hop chain; populated by SwiftProvider
  chargeBearer: Option[ChargeBearer] = None,    // SWIFT charge bearer; None for non-SWIFT rails
)

/** Lifecycle status of a bank transfer. */
enum BankTransferStatus:
  case Pending                                              // submitted, awaiting settlement
  case Settled                                             // funds confirmed by receiving bank
  case Rejected(code: RejectCode, description: String)    // rejected before settlement
  case Returned(code: ReturnCode, description: String)    // returned after settlement
  case Canceled                                           // canceled before submission

/** Rail-specific reject code (before settlement). */
case class RejectCode(value: String)

/** Rail-specific return code (post-settlement). */
case class ReturnCode(value: String)
