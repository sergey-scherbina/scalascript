package scalascript.payments.bankrails

import scalascript.payments.money.Money

/** Opaque identifier for a bank transfer. */
opaque type TransferId = String
object TransferId:
  def apply(s: String): TransferId = s
  extension (id: TransferId) def value: String = id

/** Bank account details. Fields used depend on the rail (IBAN for SEPA,
 *  routingNumber+accountNumber for ACH, pixKey for Pix). */
case class BankAccount(
  iban:          Option[String] = None,    // SEPA
  accountNumber: Option[String] = None,    // ACH / Pix / FedNow
  routingNumber: Option[String] = None,    // ACH (ABA routing number)
  bankCode:      Option[String] = None,    // Pix ISPB / FedNow routing
  pixKey:        Option[String] = None,    // Pix key (CPF/CNPJ/phone/email/EVP)
  holderName:    String,
  countryCode:   String,                   // ISO 3166-1 alpha-2
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
