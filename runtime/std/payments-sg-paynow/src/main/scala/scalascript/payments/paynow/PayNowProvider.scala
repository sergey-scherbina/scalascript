package scalascript.payments.paynow

import scalascript.payments.bankrails.*
import scalascript.payments.money.{Money, Currency}
import scalascript.payments.webhook.WebhookReceiver
import java.time.Instant

/** Singapore PayNow BankRailsProvider adapter.
 *
 *  Communicates with a MAS-licensed aggregator (NETS, DBS PayNow Business API,
 *  OCBC Business Connect, or MatchMove) via REST JSON over HTTPS.
 *
 *  Two-step flow for each payment:
 *  1. Proxy resolution   — resolve `BankAccount.paynowProxy` to participant bank code.
 *     If the proxy is not registered in the PayNow directory, `PayNowProxyNotFound` is raised.
 *  2. Payment initiation — submit the PayNow credit transfer to the FAST network.
 *     Returns `BankTransfer(status = Pending)`; terminal state arrives via webhook
 *     (`paynow.payment.credit` → Settled, `paynow.payment.return` → Returned).
 *
 *  Auth: Bearer token (`apiKey`) on every request.
 *
 *  Wire fields (JSON to aggregator):
 *    `proxyType`      = resolved from `PayNowProxyType` enum ("MOBILE" / "NRIC" / "UEN" / "VPA")
 *    `proxyValue`     = `BankAccount.paynowProxy`
 *    `amount`         = SGD cents (integer)
 *    `currency`       = "SGD" (PayNow is SGD-only)
 *    `transactionRef` = `idempotencyKey` (max 35 chars, truncated if necessary)
 *    `senderUen`      = `PayNowConfig.senderUen` (business UEN of the paying entity)
 *
 *  SGD-only validation: if the request amount is not in SGD, `IllegalArgumentException` is raised.
 *
 *  Settlement: T+0 instant, 24x7x365. MAS per-transaction limit: S$200,000 (default).
 *
 *  AML/KYC note: MAS requires Customer Due Diligence for transactions above S$5,000 from
 *  individuals. Aggregators enforce this at on-boarding and per-transaction.
 *
 *  See docs/specs/international-bank-rails.md §v1.56.8.
 */
class PayNowProvider(config: PayNowConfig) extends BankRailsProvider:

  private val api = PayNowApi(config)

  def id:             String        = "sg-paynow"
  def displayName:    String        = "Singapore PayNow (MAS FAST + proxy resolution)"
  def spiVersion:     String        = "1.55.8"
  def supportedRails: Set[RailKind] = Set(RailKind.SG_PAYNOW)

  // ── Push: PayNow credit transfer ──────────────────────────────────────────

  def initiateTransfer(req: InitiateTransferRequest): BankTransfer =
    if !supportedRails.contains(req.rail) then
      throw UnsupportedRail(req.rail, id)

    // PayNow is SGD-only
    if req.amount.currency.code != "SGD" then
      throw new IllegalArgumentException(
        s"PayNow only supports SGD transfers; got ${req.amount.currency.code}"
      )

    val proxyValue = req.recipient.paynowProxy.getOrElse(
      throw new IllegalArgumentException(
        "BankAccount.paynowProxy is required for SG_PAYNOW transfers (set on recipient)"
      )
    )

    // Infer proxy type from the proxy value format
    val proxyType = inferProxyType(proxyValue)

    // Step 1: Resolve proxy in the PayNow proxy directory
    val resolution = api.resolveProxy(proxyType, proxyValue)
    if !resolution.registered then
      throw PayNowProxyNotFound(proxyType, proxyValue)

    // Step 2: Initiate payment over FAST network
    val amountCents    = req.amount.minorUnits
    val transactionRef = req.idempotencyKey.take(35)
    val payerInfo      =
      if req.metadata.contains("payerName") then req.metadata.get("payerName")
      else config.displayName

    val respBody   = api.initiatePayment(
      proxyType      = proxyType,
      proxyValue     = proxyValue,
      amountCents    = amountCents,
      transactionRef = transactionRef,
      reference      = req.reference,
      payerInfo      = payerInfo,
    )

    val transferId = api.extractField(respBody, "transactionRef")
                       .orElse(api.extractField(respBody, "txnRef"))
                       .orElse(api.extractField(respBody, "id"))
                       .getOrElse(transactionRef)

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
    val body = api.getJson(s"/paynow/payments/${id.value}")
    parseTransferFromJson(body, id)

  def cancelTransfer(id: TransferId): Unit =
    // PayNow FAST transfers are instant and irrevocable once submitted
    throw BankRailsCancelError(id, "PayNow FAST transfers cannot be cancelled after submission — transfers are instant and irrevocable")

  // ── Pull: not supported on PayNow (push-only rail) ────────────────────────

  def initiateDirectDebit(req: InitiateDirectDebitRequest): BankTransfer =
    throw UnsupportedRail(req.rail, id)

  def getDirectDebit(id: TransferId): BankTransfer =
    throw TransferNotFound(id)

  // ── Webhook ────────────────────────────────────────────────────────────────

  def webhookReceiver: WebhookReceiver[BankRailsEvent] = PayNowWebhookReceiver()

  // ── Proxy resolution (public API for pre-payment checks) ─────────────────

  /** Resolve a PayNow proxy directly (without initiating a payment).
   *  Useful for UI "verify recipient" flows before showing a confirmation screen.
   *
   *  @param proxyType  "MOBILE", "NRIC", "UEN", or "VPA"
   *  @param proxyValue the proxy value to look up
   *  @return           resolution result indicating registration status
   */
  def resolveProxy(proxyType: String, proxyValue: String): ProxyResolutionResult =
    api.resolveProxy(proxyType, proxyValue)

  // ── Proxy type inference ──────────────────────────────────────────────────

  /** Infer the PayNow proxy type from the proxy value format.
   *
   *  Rules:
   *  - Starts with "+"  → MOBILE (international mobile format, e.g. "+6591234567")
   *  - Contains "@"     → VPA (Virtual Payment Address)
   *  - Looks like a Singapore NRIC/FIN (letter + 7 digits + letter) → NRIC
   *  - Otherwise        → UEN (Unique Entity Number for businesses)
   */
  def inferProxyType(proxyValue: String): String =
    if proxyValue.startsWith("+") then "MOBILE"
    else if proxyValue.contains("@") then "VPA"
    else if proxyValue.matches("[STFGM]\\d{7}[A-Z]") then "NRIC"
    else "UEN"

  // ── JSON parsing ──────────────────────────────────────────────────────────

  private def parseTransferFromJson(body: String, fallbackId: TransferId): BankTransfer =
    val id        = api.extractField(body, "transactionRef")
                      .orElse(api.extractField(body, "txnRef"))
                      .orElse(api.extractField(body, "id"))
                      .getOrElse(fallbackId.value)
    val statusStr = api.extractField(body, "status").getOrElse("pending")
    val status    = statusStr.toLowerCase match
      case "settled" | "completed" | "success" | "accepted" => BankTransferStatus.Settled
      case "failed" | "rejected"                            =>
        val reason = api.extractField(body, "reason").getOrElse("rejected")
        BankTransferStatus.Rejected(RejectCode(reason), reason)
      case "returned"                                       =>
        val code = api.extractField(body, "returnCode").getOrElse("unknown")
        val desc = api.extractField(body, "description").getOrElse("")
        BankTransferStatus.Returned(ReturnCode(code), desc)
      case _                                                => BankTransferStatus.Pending

    val amountStr = api.extractField(body, "amount").getOrElse("0")
    val dummy     = BankAccount(holderName = "", countryCode = "SG")
    BankTransfer(
      id        = TransferId(id),
      rail      = RailKind.SG_PAYNOW,
      amount    = parseMoney(amountStr),
      sender    = dummy,
      recipient = dummy,
      reference = api.extractField(body, "reference").getOrElse(""),
      status    = status,
      createdAt = Instant.now(),
    )

  private def parseMoney(amountStr: String): Money =
    val cents = scala.util.Try(amountStr.toLong).getOrElse(0L)
    Money(cents, Currency("SGD"))


/** Singapore PayNow adapter configuration.
 *
 *  @param apiKey      Bearer token for the aggregator API
 *  @param baseUrl     aggregator base URL (e.g. "https://api.paynow-gateway.example.com/v1")
 *  @param senderUen   Unique Entity Number (UEN) of the paying business; required by MAS
 *  @param displayName optional payer display name shown to the recipient
 */
case class PayNowConfig(
  apiKey:      String,
  baseUrl:     String,
  senderUen:   String,
  displayName: Option[String] = None,
)

object PayNowConfig:
  /** Load config from environment variables.
   *  SG_PAYNOW_API_KEY, SG_PAYNOW_BASE_URL, SG_PAYNOW_SENDER_UEN, SG_PAYNOW_DISPLAY_NAME */
  def fromEnv: PayNowConfig =
    PayNowConfig(
      apiKey      = sys.env.getOrElse("SG_PAYNOW_API_KEY",      ""),
      baseUrl     = sys.env.getOrElse("SG_PAYNOW_BASE_URL",     "https://api.paynow-gateway.example.com/v1"),
      senderUen   = sys.env.getOrElse("SG_PAYNOW_SENDER_UEN",   ""),
      displayName = sys.env.get("SG_PAYNOW_DISPLAY_NAME"),
    )
