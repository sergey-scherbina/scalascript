package scalascript.payments.ukchaps

import scalascript.payments.bankrails.*
import scalascript.payments.money.{Money, Currency}
import scalascript.payments.webhook.WebhookReceiver
import java.net.URI
import java.net.http.{HttpClient, HttpRequest as JHttpRequest, HttpResponse as JHttpResponse}
import java.net.http.HttpResponse.BodyHandlers
import java.nio.charset.StandardCharsets
import java.time.{Duration, Instant}

/** UK CHAPS (Clearing House Automated Payment System) BankRailsProvider adapter.
 *
 *  CHAPS is the UK's real-time gross settlement (RTGS) system for high-value,
 *  same-day GBP payments.  It operates on business days 08:00–17:00 UK time.
 *  Since 2020 there is no minimum payment value.
 *
 *  Communicates with a CHAPS-sponsored aggregator (ClearBank, Starling Payments,
 *  Lloyds TSB CHAPS gateway) via REST JSON over HTTPS.  The adapter builds an
 *  ISO 20022 pacs.008.001.08 message via ChapsPacs008Builder and submits it as
 *  the JSON body; the aggregator forwards to the BoE RTGS system.
 *
 *  Key behaviours:
 *  - Only GBP is supported; initiateTransfer throws if amount currency != GBP.
 *  - Idempotency key maps to EndToEndId (max 35 chars).
 *  - Returns BankTransfer(status = Pending); terminal state arrives via webhook.
 *  - cancelTransfer is attempted but BankRailsCancelError is raised if the
 *    aggregator rejects the cancel (e.g. payment already entered the RTGS queue).
 *
 *  Wire format: ISO 20022 pacs.008.001.08 with SvcLvl=CHAPS, SttlmMtd=INDA.
 *
 *  See docs/international-bank-rails.md §v1.55.5.
 */
class UkChapsProvider(config: UkChapsConfig) extends BankRailsProvider:

  private val http: HttpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(10))
    .build()

  def id:             String        = "uk-chaps"
  def displayName:    String        = "UK CHAPS (Clearing House Automated Payment System)"
  def spiVersion:     String        = "1.55.5"
  def supportedRails: Set[RailKind] = Set(RailKind.UK_CHAPS)

  // ── Push: CHAPS credit transfer ───────────────────────────────────────────

  def initiateTransfer(req: InitiateTransferRequest): BankTransfer =
    if !supportedRails.contains(req.rail) then
      throw UnsupportedRail(req.rail, id)
    if req.amount.currency.toString != "GBP" then
      throw new IllegalArgumentException(
        s"CHAPS only supports GBP; got ${req.amount.currency}"
      )

    val pacs008Xml = ChapsPacs008Builder.buildPacs008(req)
    // Aggregator API: POST /payments with pacs.008 XML as the body
    val respBody = postXml("/payments", pacs008Xml)
    val transferId = extractField(respBody, "paymentId")
                      .orElse(extractField(respBody, "id"))
                      .orElse(extractField(respBody, "endToEndId"))
                      .getOrElse(req.idempotencyKey)

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
    val body = getJson(s"/payments/${id.value}")
    parseTransferFromJson(body, id)

  /** Attempt to cancel the CHAPS payment.
   *
   *  Cancellation is only possible before the payment enters the RTGS queue
   *  (aggregator-dependent, typically a very narrow window).  If the aggregator
   *  returns a 4xx error the adapter raises BankRailsCancelError.
   */
  def cancelTransfer(id: TransferId): Unit =
    try
      postJson(s"/payments/${id.value}/cancel", "{}")
      ()
    catch
      case e: RuntimeException =>
        throw BankRailsCancelError(id, e.getMessage)

  // ── Pull: CHAPS is push-only ──────────────────────────────────────────────

  def initiateDirectDebit(req: InitiateDirectDebitRequest): BankTransfer =
    throw UnsupportedRail(req.rail, id)

  def getDirectDebit(id: TransferId): BankTransfer =
    throw TransferNotFound(id)

  // ── Webhook ───────────────────────────────────────────────────────────────

  def webhookReceiver: WebhookReceiver[BankRailsEvent] = UkChapsWebhookReceiver()

  // ── HTTP helpers ──────────────────────────────────────────────────────────

  private def postXml(path: String, xml: String): String =
    val req = JHttpRequest.newBuilder(URI.create(s"${config.baseUrl}$path"))
      .header("Authorization", s"Bearer ${config.apiKey}")
      .header("Content-Type", "application/xml")
      .header("Accept", "application/json")
      .POST(JHttpRequest.BodyPublishers.ofString(xml, StandardCharsets.UTF_8))
      .timeout(Duration.ofSeconds(30))
      .build()
    checkResponse(http.send(req, BodyHandlers.ofString(StandardCharsets.UTF_8)))

  private def postJson(path: String, json: String): String =
    val req = JHttpRequest.newBuilder(URI.create(s"${config.baseUrl}$path"))
      .header("Authorization", s"Bearer ${config.apiKey}")
      .header("Content-Type", "application/json")
      .header("Accept", "application/json")
      .POST(JHttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
      .timeout(Duration.ofSeconds(30))
      .build()
    checkResponse(http.send(req, BodyHandlers.ofString(StandardCharsets.UTF_8)))

  private def getJson(path: String): String =
    val req = JHttpRequest.newBuilder(URI.create(s"${config.baseUrl}$path"))
      .header("Authorization", s"Bearer ${config.apiKey}")
      .GET()
      .timeout(Duration.ofSeconds(30))
      .build()
    checkResponse(http.send(req, BodyHandlers.ofString(StandardCharsets.UTF_8)))

  private def checkResponse(resp: JHttpResponse[String]): String =
    if resp.statusCode() >= 400 then
      throw new RuntimeException(s"CHAPS API error ${resp.statusCode()}: ${resp.body()}")
    resp.body()

  // ── JSON helpers (no external dependency) ────────────────────────────────

  private def extractField(json: String, key: String): Option[String] =
    s""""$key"\\s*:\\s*"([^"\\\\]*)"""".r.findFirstMatchIn(json).map(_.group(1))

  private def parseTransferFromJson(body: String, fallbackId: TransferId): BankTransfer =
    val id       = extractField(body, "paymentId")
                     .orElse(extractField(body, "id"))
                     .getOrElse(fallbackId.value)
    val statusStr = extractField(body, "status").getOrElse("pending")
    val status   = statusStr.toLowerCase match
      case "settled" | "completed" | "accc" | "succeeded" => BankTransferStatus.Settled
      case "rejected" | "rjct"                             =>
        val code = extractField(body, "rejectCode").getOrElse("unknown")
        val desc = extractField(body, "reason").getOrElse("")
        BankTransferStatus.Rejected(RejectCode(code), desc)
      case "canceled" | "cancelled"                        => BankTransferStatus.Canceled
      case _                                               => BankTransferStatus.Pending
    val amountStr = extractField(body, "amount").getOrElse("0")
    val dummy     = BankAccount(holderName = "", countryCode = "GB")
    BankTransfer(
      id        = TransferId(id),
      rail      = RailKind.UK_CHAPS,
      amount    = parseMoney(amountStr),
      sender    = dummy,
      recipient = dummy,
      reference = extractField(body, "reference").getOrElse(""),
      status    = status,
      createdAt = Instant.now(),
    )

  /** Parse pence (integer) into a Money value. */
  private def parseMoney(amountStr: String): Money =
    val pence = scala.util.Try(amountStr.toLong).getOrElse(0L)
    Money(pence, Currency("GBP"))


/** UK CHAPS adapter configuration. */
case class UkChapsConfig(
  apiKey:      String,    // OAuth2 Bearer token
  baseUrl:     String,    // e.g. "https://api.clearbank.com/v1/chaps"
  sortCode:    String,    // 6-digit sender sort code (for reference / validation)
  accountNumber: String,  // 8-digit sender account number
)

object UkChapsConfig:
  /** Load config from environment variables.
   *  UK_CHAPS_API_KEY, UK_CHAPS_BASE_URL, UK_CHAPS_SORT_CODE, UK_CHAPS_ACCOUNT_NUMBER */
  def fromEnv: UkChapsConfig =
    UkChapsConfig(
      apiKey        = sys.env.getOrElse("UK_CHAPS_API_KEY",        ""),
      baseUrl       = sys.env.getOrElse("UK_CHAPS_BASE_URL",       "https://api.chaps-gateway.example.com/v1"),
      sortCode      = sys.env.getOrElse("UK_CHAPS_SORT_CODE",      ""),
      accountNumber = sys.env.getOrElse("UK_CHAPS_ACCOUNT_NUMBER", ""),
    )
