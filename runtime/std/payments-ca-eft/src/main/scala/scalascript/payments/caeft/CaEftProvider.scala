package scalascript.payments.caeft

import scalascript.payments.bankrails.*
import scalascript.payments.money.{Money, Currency}
import scalascript.payments.webhook.WebhookReceiver
import java.time.{Instant, LocalDate}
import java.util.concurrent.ConcurrentHashMap
import scala.collection.concurrent.{Map as CMap}
import scala.jdk.CollectionConverters.*

/** Canada Interac e-Transfer + EFT `BankRailsProvider` adapter.
 *
 *  Supports two Canadian payment rails:
 *
 *  == CA_INTERAC — Interac e-Transfer ==
 *
 *  The Interac e-Transfer network enables push transfers between any Canadian bank
 *  account by email address or phone number.  There is no pre-registration of
 *  account numbers — the recipient authenticates with their own bank to deposit.
 *
 *  Flow:
 *    1. Sender initiates transfer specifying recipient email or phone, amount (CAD only).
 *    2. Interac notifies the recipient; they deposit via their bank's online portal.
 *    3. `interac.transfer.sent` webhook fires when deposited; final state = Settled.
 *    4. If not deposited within 30 days: `interac.transfer.expired`.
 *    5. Sender may recall (cancel) before deposit: `interac.transfer.reclaimed`.
 *
 *  Addressing:
 *    - `req.creditorAccount.email` is used if non-empty.
 *    - Fallback: `req.creditorAccount.phone`.
 *    - If neither is set, `IllegalArgumentException` is thrown.
 *
 *  CAD-only: non-CAD amounts throw `IllegalArgumentException`.
 *
 *  == CA_EFT — CPA Standard 005 AFT (Electronic Funds Transfer) ==
 *
 *  The CPA (Canadian Payments Association) Standard 005 format is the Canadian
 *  equivalent of BACS Standard-18.  All major Canadian banks accept AFT credit
 *  and debit files via ACSS (Automated Clearing Settlement System).
 *
 *  `initiateTransfer`   — builds an AFT credit record and submits a CPA 005 file.
 *  `initiateDirectDebit`— builds an AFT debit record and submits a CPA 005 file.
 *
 *  Required BankAccount fields for EFT:
 *    - `transitNumber`    (5-digit branch transit number)
 *    - `institutionNumber`(3-digit bank institution number)
 *    - `accountNumber`    (7–12-digit account number)
 *
 *  Settlement: T+1 to T+2 depending on the aggregator and file submission time.
 *
 *  == cancelTransfer ==
 *
 *  - CA_INTERAC: recall is attempted (success if recipient has not yet deposited).
 *  - CA_EFT: returns `Left(BankRailsCancelError(...))` — EFT files cannot be recalled
 *    after submission to ACSS.
 *
 *  See docs/international-bank-rails.md §CA_INTERAC for spec.
 */
class CaEftProvider(config: CaEftConfig) extends BankRailsProvider:

  private val api = CaEftApi(config)

  def id:             String        = "ca-eft"
  def displayName:    String        = "Canada Interac e-Transfer + EFT (CPA Standard 005)"
  def spiVersion:     String        = "1.57.2"
  def supportedRails: Set[RailKind] = Set(RailKind.CA_INTERAC, RailKind.CA_EFT)

  // In-memory transfer store for idempotency and getTransfer
  private val transfers: CMap[String, BankTransfer] =
    new ConcurrentHashMap[String, BankTransfer]().asScala

  // ── Push: credit transfer ─────────────────────────────────────────────────

  def initiateTransfer(req: InitiateTransferRequest): BankTransfer =
    if !supportedRails.contains(req.rail) then throw UnsupportedRail(req.rail, id)

    // CAD-only for both rails
    if req.amount.currency.code != "CAD" then
      throw new IllegalArgumentException(
        s"CA EFT/Interac only supports CAD transfers; got ${req.amount.currency.code}"
      )

    // Idempotency: return existing transfer if key already used
    transfers.get(req.idempotencyKey) match
      case Some(existing) => return existing
      case None =>

    req.rail match
      case RailKind.CA_INTERAC => initiateInteracTransfer(req)
      case RailKind.CA_EFT     => initiateEftCreditTransfer(req)
      case other               => throw UnsupportedRail(other, id)

  // ── Interac e-Transfer (push) ─────────────────────────────────────────────

  private def initiateInteracTransfer(req: InitiateTransferRequest): BankTransfer =
    val (recipient, recipientType) = resolveInteracRecipient(req.recipient)

    val respBody = api.sendInteracTransfer(
      transferId    = req.idempotencyKey,
      amountCents   = req.amount.minorUnits,
      recipient     = recipient,
      recipientType = recipientType,
      reference     = req.reference,
    )

    val transferId = api.extractField(respBody, "transferId")
                       .orElse(api.extractField(respBody, "id"))
                       .getOrElse(req.idempotencyKey)

    val transfer = BankTransfer(
      id        = TransferId(transferId),
      rail      = RailKind.CA_INTERAC,
      amount    = req.amount,
      sender    = req.sender,
      recipient = req.recipient,
      reference = req.reference,
      status    = BankTransferStatus.Pending,
      createdAt = Instant.now(),
      metadata  = req.metadata ++ List("recipientType" -> recipientType, "recipient" -> recipient),
    )
    transfers.put(req.idempotencyKey, transfer)
    transfers.put(transferId, transfer)
    transfer

  /** Resolve the Interac e-Transfer recipient address: email takes priority over phone. */
  private def resolveInteracRecipient(account: BankAccount): (String, String) =
    account.email.filter(_.nonEmpty).map(e => (e, "email"))
      .orElse(account.phone.filter(_.nonEmpty).map(p => (p, "phone")))
      .getOrElse(
        throw new IllegalArgumentException(
          "CA_INTERAC requires BankAccount.email or BankAccount.phone on the creditor account"
        )
      )

  // ── EFT AFT credit file (push) ────────────────────────────────────────────

  private def initiateEftCreditTransfer(req: InitiateTransferRequest): BankTransfer =
    val recipient = req.recipient
    val transitNo  = recipient.transitNumber.getOrElse(
      throw new IllegalArgumentException("CA_EFT credit transfer requires creditorAccount.transitNumber (5 digits)")
    )
    val instNo = recipient.institutionNumber.getOrElse(
      throw new IllegalArgumentException("CA_EFT credit transfer requires creditorAccount.institutionNumber (3 digits)")
    )
    val accNo  = recipient.accountNumber.getOrElse(
      throw new IllegalArgumentException("CA_EFT credit transfer requires creditorAccount.accountNumber")
    )

    val record = AftRecord(
      transactionType = 450,  // AFT credit
      amount          = req.amount.minorUnits,
      transitNumber   = transitNo,
      institutionNumber = instNo,
      accountNumber   = accNo,
      payeeName       = recipient.holderName.take(30),
      sundryInfo      = req.reference.take(19),
    )

    val header = AftFileHeader(
      originatorId        = config.institutionId.take(10),
      fileCreationDate    = LocalDate.now(),
      fileSequenceNumber  = (System.currentTimeMillis() % 9999).toInt + 1,
    )

    val respBody = api.submitEftFile(header, List(record))
    val fileRef  = api.extractField(respBody, "fileId")
                     .orElse(api.extractField(respBody, "referenceId"))
                     .getOrElse(req.idempotencyKey)

    val transfer = BankTransfer(
      id        = TransferId(fileRef),
      rail      = RailKind.CA_EFT,
      amount    = req.amount,
      sender    = req.sender,
      recipient = req.recipient,
      reference = req.reference,
      status    = BankTransferStatus.Pending,
      createdAt = Instant.now(),
      metadata  = req.metadata + ("aftType" -> "credit"),
    )
    transfers.put(req.idempotencyKey, transfer)
    transfers.put(fileRef, transfer)
    transfer

  // ── getTransfer ───────────────────────────────────────────────────────────

  def getTransfer(id: TransferId): BankTransfer =
    // First check local store; fall back to aggregator API
    transfers.get(id.value) match
      case Some(t) => t
      case None =>
        val body = api.getTransferStatus(id.value)
        parseTransferFromJson(body, id)

  // ── cancelTransfer ────────────────────────────────────────────────────────

  def cancelTransfer(id: TransferId): Unit =
    val transferOpt = transfers.get(id.value)
    // For CA_EFT: cannot cancel after submission
    transferOpt.foreach { t =>
      if t.rail == RailKind.CA_EFT then
        throw BankRailsCancelError(id, "EFT cannot be cancelled after submission — AFT files are irrevocable once submitted to ACSS")
    }
    // For CA_INTERAC: attempt recall via aggregator
    api.recallInteracTransfer(id.value)
    // Update local status
    transferOpt.foreach { t =>
      val canceled = t.copy(status = BankTransferStatus.Canceled)
      transfers.put(id.value, canceled)
    }

  // ── Pull: EFT AFT debit (direct debit) ───────────────────────────────────

  def initiateDirectDebit(req: InitiateDirectDebitRequest): BankTransfer =
    if req.rail != RailKind.CA_EFT then throw UnsupportedRail(req.rail, id)

    if req.amount.currency.code != "CAD" then
      throw new IllegalArgumentException(
        s"CA_EFT direct debit only supports CAD; got ${req.amount.currency.code}"
      )

    // Idempotency
    transfers.get(req.idempotencyKey) match
      case Some(existing) => return existing
      case None =>

    val debtor = req.debtorAccount
    val transitNo = debtor.transitNumber.getOrElse(
      throw new IllegalArgumentException("CA_EFT direct debit requires debtorAccount.transitNumber (5 digits)")
    )
    val instNo = debtor.institutionNumber.getOrElse(
      throw new IllegalArgumentException("CA_EFT direct debit requires debtorAccount.institutionNumber (3 digits)")
    )
    val accNo  = debtor.accountNumber.getOrElse(
      throw new IllegalArgumentException("CA_EFT direct debit requires debtorAccount.accountNumber")
    )

    val record = AftRecord(
      transactionType = 470,  // AFT debit
      amount          = req.amount.minorUnits,
      transitNumber   = transitNo,
      institutionNumber = instNo,
      accountNumber   = accNo,
      payeeName       = debtor.holderName.take(30),
      sundryInfo      = req.reference.take(19),
    )

    val header = AftFileHeader(
      originatorId        = config.institutionId.take(10),
      fileCreationDate    = LocalDate.now(),
      fileSequenceNumber  = (System.currentTimeMillis() % 9999).toInt + 1,
    )

    val respBody = api.submitEftFile(header, List(record))
    val fileRef  = api.extractField(respBody, "fileId")
                     .orElse(api.extractField(respBody, "referenceId"))
                     .getOrElse(req.idempotencyKey)

    val transfer = BankTransfer(
      id        = TransferId(fileRef),
      rail      = RailKind.CA_EFT,
      amount    = req.amount,
      sender    = req.debtorAccount,
      recipient = req.creditorAccount,
      reference = req.reference,
      status    = BankTransferStatus.Pending,
      createdAt = Instant.now(),
      metadata  = req.metadata + ("aftType" -> "debit"),
    )
    transfers.put(req.idempotencyKey, transfer)
    transfers.put(fileRef, transfer)
    transfer

  def getDirectDebit(id: TransferId): BankTransfer =
    transfers.getOrElse(id.value, throw TransferNotFound(id))

  // ── Webhook ───────────────────────────────────────────────────────────────

  def webhookReceiver: WebhookReceiver[BankRailsEvent] = CaEftWebhookReceiver()

  // ── JSON parsing helpers ──────────────────────────────────────────────────

  private def parseTransferFromJson(body: String, fallbackId: TransferId): BankTransfer =
    val id        = api.extractField(body, "transferId")
                      .orElse(api.extractField(body, "id"))
                      .getOrElse(fallbackId.value)
    val statusStr = api.extractField(body, "status").getOrElse("pending")
    val status    = statusStr.toLowerCase match
      case "settled" | "completed" | "deposited" | "accepted" => BankTransferStatus.Settled
      case "failed" | "rejected"                              =>
        val reason = api.extractField(body, "reason").getOrElse("rejected")
        BankTransferStatus.Rejected(RejectCode(reason), reason)
      case "returned"                                         =>
        val code = api.extractField(body, "returnCode").getOrElse("unknown")
        val desc = api.extractField(body, "description").getOrElse("")
        BankTransferStatus.Returned(ReturnCode(code), desc)
      case "canceled" | "cancelled" | "reclaimed" | "expired" => BankTransferStatus.Canceled
      case _                                                   => BankTransferStatus.Pending

    val amountStr = api.extractField(body, "amount").getOrElse("0")
    val dummy     = BankAccount(holderName = "", countryCode = "CA")
    BankTransfer(
      id        = TransferId(id),
      rail      = RailKind.CA_INTERAC,   // best-effort; caller may override
      amount    = parseMoney(amountStr),
      sender    = dummy,
      recipient = dummy,
      reference = api.extractField(body, "reference").getOrElse(""),
      status    = status,
      createdAt = Instant.now(),
    )

  private def parseMoney(amountStr: String): Money =
    val cents = scala.util.Try(amountStr.toLong).getOrElse(0L)
    Money(cents, Currency("CAD"))
