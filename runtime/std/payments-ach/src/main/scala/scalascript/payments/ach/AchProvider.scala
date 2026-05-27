package scalascript.payments.ach

import scalascript.payments.bankrails.*
import scalascript.payments.webhook.WebhookReceiver
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import scala.collection.concurrent.{Map as CMap}
import scala.jdk.CollectionConverters.*

/** ACH BankRailsProvider adapter.
 *
 *  Implements ACH Credit Transfer and ACH Direct Debit via Nacha flat-file delivery
 *  over SFTP to an ACH originator (ODFI) or aggregator.
 *
 *  Same-day ACH: set `InitiateTransferRequest.sameDay = true` or `AchConfig.sameDayAch = true`.
 *  When same-day, the effective entry date is today and "SAMEDAY" appears in the batch header.
 *
 *  Return codes (R01–R85) and NOC codes (C01–C09) are parsed from return files via
 *  `AchWebhookReceiver` and surfaced as `BankRailsEvent.AchReturn` /
 *  `BankRailsEvent.AchNotificationOfChange`.
 *
 *  Mandate model: ACH Debit requires written authorization (Reg E).
 *  Mandates are stored locally; `MandateStatus.Revoked` means the customer
 *  contacted their bank per Reg E.
 *
 *  See docs/bank-rails.md §v1.54.2 for full spec.
 */
class AchProvider(val config: AchConfig) extends BankRailsProvider:

  def id:             String        = "ach"
  def displayName:    String        = "ACH (Nacha flat-file — Credit + Debit)"
  def spiVersion:     String        = "1.54.2"
  def supportedRails: Set[RailKind] = Set(RailKind.ACH_CREDIT, RailKind.ACH_DEBIT)

  // In-memory transfer store (keyed by idempotency key for dedup + by transfer ID for lookup)
  private val transfers: CMap[String, BankTransfer] = new ConcurrentHashMap[String, BankTransfer]().asScala
  private val mandates:  CMap[String, DirectDebitMandate] = new ConcurrentHashMap[String, DirectDebitMandate]().asScala

  // ── Push: ACH Credit Transfer ─────────────────────────────────────────────

  def initiateTransfer(req: InitiateTransferRequest): BankTransfer =
    if !supportedRails.contains(req.rail) then throw UnsupportedRail(req.rail, id)

    // Idempotency check
    transfers.get(req.idempotencyKey) match
      case Some(existing) => return existing
      case None =>

    val recipient = req.recipient
    val routing   = recipient.routingNumber.getOrElse(
      throw new IllegalArgumentException("ACH transfer requires recipient.routingNumber")
    )
    val account   = recipient.accountNumber.getOrElse(
      throw new IllegalArgumentException("ACH transfer requires recipient.accountNumber")
    )

    val sameDayFlag = req.sameDay || config.sameDayAch
    val txCode      = NachaFile.TransactionCode.CheckingCredit

    val entry = EntryDetail(
      transactionCode = txCode,
      rdfiRouting     = routing,
      accountNumber   = account,
      amountCents     = req.amount.minorUnits,
      individualId    = req.idempotencyKey.take(15),
      individualName  = recipient.holderName,
      traceNumber     = buildTraceNumber(config.achRoutingNumber, 1),
    )

    val nachaFile = NachaFile.build(
      config       = config,
      entries      = List(entry),
      serviceClass = NachaFile.ServiceClass.Credits,
      sameDay      = sameDayFlag,
      effectiveDate = req.scheduledDate,
    )

    // Deliver file (SFTP stub — in production, use JSch or Apache VFS SFTP)
    deliverNachaFile(nachaFile, "credit")

    val transferId = s"ach-${System.currentTimeMillis()}-${req.idempotencyKey.take(8)}"
    val transfer = BankTransfer(
      id        = TransferId(transferId),
      rail      = RailKind.ACH_CREDIT,
      amount    = req.amount,
      sender    = req.sender,
      recipient = req.recipient,
      reference = req.reference,
      status    = BankTransferStatus.Pending,
      createdAt = Instant.now(),
      metadata  = req.metadata,
    )
    transfers.put(req.idempotencyKey, transfer)
    transfers.put(transferId, transfer)
    transfer

  def getTransfer(id: TransferId): BankTransfer =
    transfers.getOrElse(id.value, throw TransferNotFound(id))

  def cancelTransfer(id: TransferId): Unit =
    val transfer = transfers.getOrElse(id.value, throw TransferNotFound(id))
    transfer.status match
      case BankTransferStatus.Pending =>
        val canceled = transfer.copy(status = BankTransferStatus.Canceled)
        transfers.put(id.value, canceled)
      case _ =>
        throw BankRailsCancelError(id, s"cannot cancel transfer with status ${transfer.status}")

  // ── Pull: ACH Direct Debit ────────────────────────────────────────────────

  def initiateDirectDebit(req: InitiateDirectDebitRequest): BankTransfer =
    if req.rail != RailKind.ACH_DEBIT then throw UnsupportedRail(req.rail, id)

    // Check mandate is active
    val mandate = mandates.getOrElse(
      req.mandateId.value,
      // Auto-register mandate as Active (merchant is responsible for authorization on file)
      {
        val m = DirectDebitMandate(
          id              = req.mandateId,
          rail            = RailKind.ACH_DEBIT,
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

    val debtor  = req.debtorAccount
    val routing = debtor.routingNumber.getOrElse(
      throw new IllegalArgumentException("ACH debit requires debtorAccount.routingNumber")
    )
    val account = debtor.accountNumber.getOrElse(
      throw new IllegalArgumentException("ACH debit requires debtorAccount.accountNumber")
    )

    val sameDayFlag = req.sameDay || config.sameDayAch
    val txCode      = NachaFile.TransactionCode.CheckingDebit

    val entry = EntryDetail(
      transactionCode = txCode,
      rdfiRouting     = routing,
      accountNumber   = account,
      amountCents     = req.amount.minorUnits,
      individualId    = req.mandateId.value.take(15),
      individualName  = debtor.holderName,
      traceNumber     = buildTraceNumber(config.achRoutingNumber, 1),
    )

    val nachaFile = NachaFile.build(
      config        = config,
      entries       = List(entry),
      serviceClass  = NachaFile.ServiceClass.Debits,
      sameDay       = sameDayFlag,
      effectiveDate = req.scheduledDate,
    )

    deliverNachaFile(nachaFile, "debit")

    val transferId = s"ach-debit-${System.currentTimeMillis()}-${req.idempotencyKey.take(8)}"
    val transfer = BankTransfer(
      id        = TransferId(transferId),
      rail      = RailKind.ACH_DEBIT,
      amount    = req.amount,
      sender    = req.debtorAccount,
      recipient = req.creditorAccount,
      reference = req.reference,
      status    = BankTransferStatus.Pending,
      createdAt = Instant.now(),
      metadata  = req.metadata,
    )
    transfers.put(req.idempotencyKey, transfer)
    transfers.put(transferId, transfer)
    transfer

  def getDirectDebit(id: TransferId): BankTransfer =
    transfers.getOrElse(id.value, throw TransferNotFound(id))

  // ── Webhook ───────────────────────────────────────────────────────────────

  def webhookReceiver: WebhookReceiver[BankRailsEvent] = AchWebhookReceiver()

  // ── Helpers ───────────────────────────────────────────────────────────────

  /** Build a 15-digit trace number: ODFI routing (8 digits) + sequence number (7 digits). */
  private def buildTraceNumber(routing: String, seq: Int): String =
    val r = routing.take(8).padTo(8, '0')
    val s = seq.toString.reverse.padTo(7, '0').reverse
    r + s

  /** Deliver the Nacha file to the configured SFTP endpoint.
   *  In production, use a real SFTP client (e.g. JSch, Apache VFS, or the
   *  aggregator's REST upload API).  Here we record the file content for
   *  testability. */
  private[ach] def deliverNachaFile(content: String, direction: String): Unit =
    // Stub: real impl would SFTP to config.achSftpHost
    lastDeliveredFile = Some(content)
    lastDeliveredDirection = Some(direction)

  // Visible for tests
  private[ach] var lastDeliveredFile:      Option[String] = None
  private[ach] var lastDeliveredDirection: Option[String] = None

/** ACH adapter configuration.
 *  See docs/bank-rails.md §v1.54.2 Auth section. */
case class AchConfig(
  achSftpHost:     String,          // SFTP host for Nacha file delivery
  achSftpUser:     String,          // SFTP user
  achSftpKeyPath:  String,          // path to SFTP private key file
  achCompanyName:  String,          // company name in Nacha batch header (max 16 chars)
  achCompanyId:    String,          // company ID / tax ID in Nacha batch header (10 chars)
  achRoutingNumber: String,         // ODFI ABA routing number (9 digits)
  sameDayAch:      Boolean = false, // default same-day flag (can be overridden per transfer)
)

object AchConfig:
  /** Load config from environment variables.
   *  ACH_SFTP_HOST, ACH_SFTP_USER, ACH_SFTP_KEY_PATH,
   *  ACH_COMPANY_NAME, ACH_COMPANY_ID, ACH_ROUTING_NUMBER, ACH_SAME_DAY */
  def fromEnv: AchConfig =
    AchConfig(
      achSftpHost      = sys.env.getOrElse("ACH_SFTP_HOST",     "sftp.ach-gateway.example.com"),
      achSftpUser      = sys.env.getOrElse("ACH_SFTP_USER",     "ach-user"),
      achSftpKeyPath   = sys.env.getOrElse("ACH_SFTP_KEY_PATH", "/etc/ach/id_rsa"),
      achCompanyName   = sys.env.getOrElse("ACH_COMPANY_NAME",  "ACME CORP"),
      achCompanyId     = sys.env.getOrElse("ACH_COMPANY_ID",    "1234567890"),
      achRoutingNumber = sys.env.getOrElse("ACH_ROUTING_NUMBER","021000021"),
      sameDayAch       = sys.env.getOrElse("ACH_SAME_DAY",      "false") == "true",
    )
