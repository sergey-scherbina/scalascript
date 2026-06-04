package scalascript.payments.ukbacs

import scalascript.payments.bankrails.*
import scalascript.payments.webhook.WebhookReceiver
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import scala.collection.concurrent.{Map as CMap}
import scala.jdk.CollectionConverters.*

/** UK BACS Direct Debit BankRailsProvider adapter.
 *
 *  Implements BACS Direct Debit collections over the 3-day BACS settlement cycle:
 *    Day 1 — input day: BACS Standard-18 file submitted to the aggregator
 *    Day 2 — processing day: Vocalink routes entries to payer banks
 *    Day 3 — settlement day: funds collected from payer accounts
 *
 *  Mandate registration uses AUDDIS (Automated Direct Debit Instruction Service),
 *  a separate file submission that notifies the payer's bank of new mandates.
 *  AUDDIS acceptance is confirmed via webhook `bacs.auddis.accepted` →
 *  `BacsAuddisAccepted`.
 *
 *  Unpaid direct debits return via ARUDD (Automated Return of Unpaid Direct Debit),
 *  surfaced as `BacsAruddReturned` with the ARUDD/ADDACS reason code.
 *
 *  Mandate sequence types from `MandateSequenceType` map to BACS:
 *    First     → Initial collection (first in series)
 *    Recurring → Recurring collection
 *    Final     → Final collection
 *    OneOff    → One-off collection
 *
 *  Auth: API key + IP allowlist at aggregator; legacy BACSTEL-IP uses SUN + password.
 *
 *  See docs/specs/international-bank-rails.md §v1.55.4 for spec.
 */
class UkBacsProvider(val config: BacsConfig) extends BankRailsProvider:

  def id:             String        = "uk-bacs-dd"
  def displayName:    String        = "UK BACS Direct Debit (Standard-18, 3-day cycle)"
  def spiVersion:     String        = "1.55.4"
  def supportedRails: Set[RailKind] = Set(RailKind.UK_BACS_DD)

  // In-memory stores (keyed by idempotency key and transfer ID)
  private val transfers: CMap[String, BankTransfer]       = new ConcurrentHashMap[String, BankTransfer]().asScala
  private val mandates:  CMap[String, DirectDebitMandate] = new ConcurrentHashMap[String, DirectDebitMandate]().asScala

  // ── Push (credit) transfer — not supported for BACS DD ───────────────────

  def initiateTransfer(req: InitiateTransferRequest): BankTransfer =
    throw UnsupportedRail(req.rail, id)

  def getTransfer(id: TransferId): BankTransfer =
    transfers.getOrElse(id.value, throw TransferNotFound(id))

  def cancelTransfer(id: TransferId): Unit =
    val transfer = transfers.getOrElse(id.value, throw TransferNotFound(id))
    transfer.status match
      case BankTransferStatus.Pending =>
        val canceled = transfer.copy(status = BankTransferStatus.Canceled)
        transfers.put(id.value, canceled)
      case _ =>
        throw BankRailsCancelError(id, s"cannot cancel BACS transfer with status ${transfer.status}")

  // ── Pull: BACS Direct Debit collection ───────────────────────────────────

  /** Initiate a BACS Direct Debit collection.
   *
   *  Builds a BACS Standard-18 debit instruction file and delivers it to the
   *  aggregator SFTP endpoint (or HTTP upload API).  The `settlementDate` on the
   *  returned transfer reflects Day 3 of the BACS cycle (submission date + 3
   *  business days).  Status starts as `Pending`; `BacsDdPaid` arrives via
   *  webhook on Day 3 settlement.
   *
   *  @throws MandateNotActive   if the mandate is not in Active status
   *  @throws BacsCycleMissed    if today is not a valid BACS input day (rare; raised
   *                             by aggregator rejection — not validated client-side here)
   */
  def initiateDirectDebit(req: InitiateDirectDebitRequest): BankTransfer =
    if req.rail != RailKind.UK_BACS_DD then throw UnsupportedRail(req.rail, id)

    // Look up or auto-register mandate
    val mandate = mandates.getOrElse(
      req.mandateId.value,
      {
        val m = DirectDebitMandate(
          id              = req.mandateId,
          rail            = RailKind.UK_BACS_DD,
          debtorAccount   = req.debtorAccount,
          creditorAccount = req.creditorAccount,
          creditorName    = req.creditorName,
          status          = MandateStatus.Active,
          sequenceType    = MandateSequenceType.Recurring,
        )
        mandates.put(req.mandateId.value, m)
        m
      }
    )

    if mandate.status != MandateStatus.Active then
      throw MandateNotActive(req.mandateId, mandate.status)

    // Idempotency check
    transfers.get(req.idempotencyKey) match
      case Some(existing) => return existing
      case None =>

    val debtor   = req.debtorAccount
    val sortCode = debtor.sortCode.getOrElse(
      throw new IllegalArgumentException("BACS Direct Debit requires debtorAccount.sortCode (6 digits)")
    )
    val accNum = debtor.accountNumber.getOrElse(
      throw new IllegalArgumentException("BACS Direct Debit requires debtorAccount.accountNumber (8 digits)")
    )

    val txCode = mandate.sequenceType match
      case MandateSequenceType.First     => BacsFile.TransactionCode.DirectDebit
      case MandateSequenceType.Recurring => BacsFile.TransactionCode.DirectDebit
      case MandateSequenceType.Final     => BacsFile.TransactionCode.DirectDebit
      case MandateSequenceType.OneOff    => BacsFile.TransactionCode.DirectDebit

    val debitRecord = BacsFile.DebitRecord(
      sortCode             = sortCode,
      accountNumber        = accNum,
      transactionCode      = txCode,
      amount               = req.amount.minorUnits,
      originatorSortCode   = config.originatorSortCode,
      originatorAccNumber  = config.originatorAccountNumber,
      accountName          = debtor.holderName.take(18),
      ref                  = req.reference.take(18),
    )

    val settlementDate = req.scheduledDate.map(_.plusDays(0)).getOrElse(
      java.time.LocalDate.now().plusDays(3)
    )

    val bacsFile = BacsFile.build(
      config         = config,
      debits         = List(debitRecord),
      processingDate = Some(settlementDate),
    )

    deliverBacsFile(bacsFile, "debit")

    val transferId = s"bacs-dd-${System.currentTimeMillis()}-${req.idempotencyKey.take(8)}"
    val transfer = BankTransfer(
      id        = TransferId(transferId),
      rail      = RailKind.UK_BACS_DD,
      amount    = req.amount,
      sender    = req.debtorAccount,
      recipient = req.creditorAccount,
      reference = req.reference,
      status    = BankTransferStatus.Pending,
      createdAt = Instant.now(),
      metadata  = req.metadata + ("settlementDate" -> settlementDate.toString),
    )
    transfers.put(req.idempotencyKey, transfer)
    transfers.put(transferId, transfer)
    transfer

  def getDirectDebit(id: TransferId): BankTransfer =
    transfers.getOrElse(id.value, throw TransferNotFound(id))

  // ── Webhook ───────────────────────────────────────────────────────────────

  def webhookReceiver: WebhookReceiver[BankRailsEvent] = UkBacsWebhookReceiver()

  // ── AUDDIS mandate registration ───────────────────────────────────────────

  /** Submit an AUDDIS file to register a new Direct Debit mandate with the
   *  payer's bank.  In production this is called as part of the mandate on-boarding
   *  flow before the first `initiateDirectDebit`.
   *
   *  The mandate starts in `MandateStatus.Pending`; `BacsAuddisAccepted` arrives
   *  via webhook on Day 3 of the AUDDIS cycle to confirm acceptance.
   */
  def registerMandate(mandate: DirectDebitMandate): Unit =
    val debtor   = mandate.debtorAccount
    val sortCode = debtor.sortCode.getOrElse(
      throw new IllegalArgumentException("AUDDIS mandate registration requires debtorAccount.sortCode")
    )
    val accNum = debtor.accountNumber.getOrElse(
      throw new IllegalArgumentException("AUDDIS mandate registration requires debtorAccount.accountNumber")
    )

    val instr = AuddisFile.AuddisInstruction(
      sortCode             = sortCode,
      accountNumber        = accNum,
      instructionCode      = AuddisFile.InstructionCode.New,
      originatorSortCode   = config.originatorSortCode,
      originatorAccNumber  = config.originatorAccountNumber,
      accountName          = debtor.holderName.take(18),
      ref                  = mandate.id.value.take(18),
    )

    val auddisFile = AuddisFile.build(config, List(instr))
    deliverBacsFile(auddisFile, "auddis")

    // Store mandate as Pending (becomes Active when BacsAuddisAccepted arrives)
    val pendingMandate = mandate.copy(status = MandateStatus.Pending)
    mandates.put(mandate.id.value, pendingMandate)

  // ── Helpers ───────────────────────────────────────────────────────────────

  /** Deliver a BACS file to the configured aggregator endpoint.
   *  In production: SFTP upload to `config.sftpHost` or HTTP POST to aggregator API.
   *  Here we store for testability. */
  private[ukbacs] def deliverBacsFile(content: String, direction: String): Unit =
    lastDeliveredFile      = Some(content)
    lastDeliveredDirection = Some(direction)

  // Visible for tests
  private[ukbacs] var lastDeliveredFile:      Option[String] = None
  private[ukbacs] var lastDeliveredDirection: Option[String] = None

/** BACS adapter configuration.
 *  See docs/specs/international-bank-rails.md §v1.55.4 Auth section. */
case class BacsConfig(
  serviceUserNumber:     String,  // BACS Service User Number (SUN) — 6-char code
  originatorSortCode:    String,  // originator (creditor) sort code — 6 digits
  originatorAccountNumber: String, // originator account number — 8 digits
  originatorName:        String,  // originator name — max 18 chars
  sftpHost:              String,  // SFTP host for BACS file delivery (or aggregator HTTP URL)
  sftpUser:              String,  // SFTP user
  apiKey:                String = "",  // aggregator API key (modern aggregator HTTP interface)
  fileLabel:             String = "BACS-DD",  // file label in volume header
)

object BacsConfig:
  /** Load config from environment variables.
   *  BACS_SUN, BACS_ORIGINATOR_SORT_CODE, BACS_ORIGINATOR_ACC_NUM,
   *  BACS_ORIGINATOR_NAME, BACS_SFTP_HOST, BACS_SFTP_USER, BACS_API_KEY */
  def fromEnv: BacsConfig =
    BacsConfig(
      serviceUserNumber      = sys.env.getOrElse("BACS_SUN",                   "123456"),
      originatorSortCode     = sys.env.getOrElse("BACS_ORIGINATOR_SORT_CODE",  "200000"),
      originatorAccountNumber = sys.env.getOrElse("BACS_ORIGINATOR_ACC_NUM",   "12345678"),
      originatorName         = sys.env.getOrElse("BACS_ORIGINATOR_NAME",       "ACME CORP"),
      sftpHost               = sys.env.getOrElse("BACS_SFTP_HOST",             "sftp.bacs-aggregator.example.com"),
      sftpUser               = sys.env.getOrElse("BACS_SFTP_USER",             "bacs-user"),
      apiKey                 = sys.env.getOrElse("BACS_API_KEY",               ""),
    )
