package scalascript.payments.aunpp

import scalascript.payments.bankrails.*
import scalascript.payments.money.{Money, Currency}
import scalascript.payments.webhook.WebhookReceiver
import java.time.Instant

/** Australia New Payments Platform (NPP) BankRailsProvider adapter.
 *
 *  Communicates with an NPP-connected aggregator (e.g. ANZ Transactive, CBA CommBiz,
 *  Monoova, Zepto, Cuscal) via REST JSON over HTTPS.
 *
 *  Two addressing modes for each payment:
 *  1. PayID addressing — `BankAccount.payid` is present (mobile / email / ABN).
 *     The PayID is resolved to BSB + account number via the aggregator's PayID
 *     resolution endpoint before payment submission.
 *  2. BSB + account fallback — uses `BankAccount.bsbNumber` + `BankAccount.accountNumber`
 *     directly (same as a standard domestic bank transfer).
 *
 *  Wire format: ISO 20022 pacs.008 JSON envelope submitted to aggregator REST endpoint.
 *  The aggregator converts to the NPP-native ISO 20022 XML message and routes it to
 *  the receiving NPP participant.
 *
 *  AUD-only: NPP transfers are denominated in Australian Dollars. Non-AUD amounts
 *  raise `BankRailsError.UnsupportedCurrency`.
 *
 *  Settlement: T+0, typically < 60 seconds, 24x7x365.
 *  Per-transaction limit: AUD 1,000,000 (standard NPP cap; higher limits require
 *  bilateral agreement with the receiving NPP participant).
 *
 *  See docs/specs/payment-rails-apac.md §AU_NPP.
 */
class AuNppProvider(config: AuNppConfig) extends BankRailsProvider:

  private val api = AuNppApi(config)

  def id:             String        = "au-npp"
  def displayName:    String        = "Australia NPP (New Payments Platform / PayID)"
  def spiVersion:     String        = "1.57.1"
  def supportedRails: Set[RailKind] = Set(RailKind.AU_NPP)

  // ── Push: NPP credit transfer ─────────────────────────────────────────────

  def initiateTransfer(req: InitiateTransferRequest): BankTransfer =
    if !supportedRails.contains(req.rail) then
      throw UnsupportedRail(req.rail, id)

    // NPP is AUD-only
    if req.amount.currency.code != "AUD" then
      throw UnsupportedCurrency(req.amount.currency.code, "AU_NPP", "AUD")

    val amountCents = req.amount.minorUnits
    val endToEndId  = req.idempotencyKey.take(35)

    // Determine creditor addressing: PayID → resolve to BSB + account; else direct BSB + account
    val (creditorBsb, creditorAccount, payidUsed) =
      req.recipient.payid match
        case Some(payid) =>
          val (bsb, acct) = api.resolvePayId(payid)
          (bsb, acct, Some(payid))
        case None =>
          val bsb  = req.recipient.bsbNumber.getOrElse(
            throw new IllegalArgumentException(
              "BankAccount.payid or BankAccount.bsbNumber + accountNumber required for AU_NPP transfers"
            )
          )
          val acct = req.recipient.accountNumber.getOrElse(
            throw new IllegalArgumentException(
              "BankAccount.accountNumber required for AU_NPP transfers when payid is not set"
            )
          )
          (bsb, acct, None)

    val debtorBsb     = req.sender.bsbNumber.getOrElse("")
    val debtorAccount = req.sender.accountNumber.getOrElse("")

    val respBody = api.sendNppPayment(
      endToEndId      = endToEndId,
      amountCents     = amountCents,
      creditorBsb     = creditorBsb,
      creditorAccount = creditorAccount,
      creditorName    = req.recipient.holderName,
      debtorBsb       = debtorBsb,
      debtorAccount   = debtorAccount,
      debtorName      = req.sender.holderName,
      reference       = req.reference,
      payidUsed       = payidUsed,
    )

    val transferId = api.extractField(respBody, "transferId")
                       .orElse(api.extractField(respBody, "endToEndId"))
                       .orElse(api.extractField(respBody, "msgId"))
                       .orElse(api.extractField(respBody, "id"))
                       .getOrElse(endToEndId)

    BankTransfer(
      id        = TransferId(transferId),
      rail      = req.rail,
      amount    = req.amount,
      sender    = req.sender,
      recipient = req.recipient,
      reference = req.reference,
      status    = BankTransferStatus.Pending,
      createdAt = Instant.now(),
      metadata  = req.metadata,
    )

  def getTransfer(id: TransferId): BankTransfer =
    val body = api.getPaymentStatus(id.value)
    parseTransferFromJson(body, id)

  def cancelTransfer(id: TransferId): Unit =
    // NPP transfers are irrevocable once submitted to the NPP network
    throw BankRailsCancelError(id, "NPP transfers cannot be cancelled after initiation")

  // ── Pull: not supported on NPP (push-only rail) ───────────────────────────

  def initiateDirectDebit(req: InitiateDirectDebitRequest): BankTransfer =
    throw UnsupportedRail(req.rail, id)

  def getDirectDebit(id: TransferId): BankTransfer =
    throw TransferNotFound(id)

  // ── Webhook ────────────────────────────────────────────────────────────────

  def webhookReceiver: WebhookReceiver[BankRailsEvent] = AuNppWebhookReceiver()

  // ── JSON parsing ──────────────────────────────────────────────────────────

  private def parseTransferFromJson(body: String, fallbackId: TransferId): BankTransfer =
    val id        = api.extractField(body, "transferId")
                      .orElse(api.extractField(body, "endToEndId"))
                      .orElse(api.extractField(body, "id"))
                      .getOrElse(fallbackId.value)
    val statusStr = api.extractField(body, "status").getOrElse("pending")
    val status    = statusStr.toLowerCase match
      case "settled" | "completed" | "success" | "accepted" | "credit" => BankTransferStatus.Settled
      case "failed" | "rejected"                                        =>
        val reason = api.extractField(body, "reason")
                       .orElse(api.extractField(body, "rejectCode"))
                       .getOrElse("rejected")
        BankTransferStatus.Rejected(RejectCode(reason), reason)
      case "returned"                                                   =>
        val code = api.extractField(body, "returnCode").getOrElse("unknown")
        val desc = api.extractField(body, "description").getOrElse("")
        BankTransferStatus.Returned(ReturnCode(code), desc)
      case _                                                            => BankTransferStatus.Pending

    val amountStr = api.extractField(body, "amount")
                      .orElse(api.extractField(body, "value"))
                      .getOrElse("0")
    val dummy     = BankAccount(holderName = "", countryCode = "AU")
    BankTransfer(
      id        = TransferId(id),
      rail      = RailKind.AU_NPP,
      amount    = parseMoney(amountStr),
      sender    = dummy,
      recipient = dummy,
      reference = api.extractField(body, "remittanceInfo")
                    .orElse(api.extractField(body, "reference"))
                    .getOrElse(""),
      status    = status,
      createdAt = Instant.now(),
    )

  private def parseMoney(amountStr: String): Money =
    val cents = scala.util.Try(amountStr.toLong).getOrElse(0L)
    Money(cents, Currency("AUD"))
