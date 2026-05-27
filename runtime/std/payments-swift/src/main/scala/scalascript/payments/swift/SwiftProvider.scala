package scalascript.payments.swift

import scalascript.payments.bankrails.*
import scalascript.payments.money.{Money, Currency}
import scalascript.payments.webhook.WebhookReceiver
import java.net.{URI}
import java.net.http.{HttpClient, HttpRequest as JHttpRequest, HttpResponse as JHttpResponse}
import java.net.http.HttpResponse.BodyHandlers
import java.nio.charset.StandardCharsets
import java.time.{Duration, Instant}

/** SWIFT BankRailsProvider adapter.
 *
 *  Supports SWIFT_MT103 (legacy ISO 15022) and SWIFT_PACS008 (ISO 20022 CBPR+) rails.
 *  Communicates with a sponsor/aggregator REST API (Wise Business / Currencycloud /
 *  Airwallex Business API / TransferMate). Does not connect directly to SWIFT Alliance.
 *
 *  Auth: Bearer token (OAuth2 client credentials) configured via SwiftConfig.
 *
 *  UETR: If the request has no UETR, one is generated via Uetr.generate() (UUID v4)
 *  and stored on the returned BankTransfer.
 *
 *  See docs/international-bank-rails.md §8 v1.55.1.
 */
class SwiftProvider(config: SwiftConfig) extends BankRailsProvider:

  private val http = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(10))
    .build()

  def id:             String        = "swift"
  def displayName:    String        = "SWIFT (MT103 + ISO 20022 pacs.008 CBPR+)"
  def spiVersion:     String        = "1.55.1"
  def supportedRails: Set[RailKind] = Set(RailKind.SWIFT_MT103, RailKind.SWIFT_PACS008)

  // ── Push: SWIFT Credit Transfer ─────────────────────────────────────────────

  def initiateTransfer(req: InitiateTransferRequest): BankTransfer =
    if !supportedRails.contains(req.rail) then
      throw UnsupportedRail(req.rail, id)

    // Validate UETR if provided
    val resolvedUetr = req.uetr match
      case Some(u) =>
        if !Uetr.isValid(u.value) then throw SwiftUetrInvalid(u.value)
        u
      case None => Uetr.generate()

    val body = req.rail match
      case RailKind.SWIFT_MT103   => buildMt103Payload(req, resolvedUetr)
      case RailKind.SWIFT_PACS008 => buildPacs008Payload(req, resolvedUetr)
      case other                  => throw UnsupportedRail(other, id)

    val respBody   = postJson("/transfers", body)
    val transferId = extractField(respBody, "transfer_id")
                       .orElse(extractField(respBody, "id"))
                       .getOrElse(req.idempotencyKey)

    BankTransfer(
      id           = TransferId(transferId),
      rail         = req.rail,
      amount       = req.amount,
      sender       = req.sender,
      recipient    = req.recipient,
      reference    = req.reference,
      status       = BankTransferStatus.Pending,
      createdAt    = Instant.now(),
      metadata     = req.metadata,
      uetr         = Some(resolvedUetr),
      chargeBearer = Some(req.chargeBearer),
    )

  def getTransfer(id: TransferId): BankTransfer =
    val body = getJson(s"/transfers/${id.value}")
    parseTransferFromJson(body, id)

  def cancelTransfer(id: TransferId): Unit =
    postJson(s"/transfers/${id.value}/cancel", "{}")
    ()

  // ── Pull: Not applicable for SWIFT (SWIFT has no direct debit model) ────────

  def initiateDirectDebit(req: InitiateDirectDebitRequest): BankTransfer =
    throw UnsupportedRail(req.rail, id)

  def getDirectDebit(id: TransferId): BankTransfer =
    throw TransferNotFound(id)

  // ── Webhook ──────────────────────────────────────────────────────────────────

  def webhookReceiver: WebhookReceiver[BankRailsEvent] = SwiftWebhookReceiver()

  // ── Wire format builders ─────────────────────────────────────────────────────

  private def buildMt103Payload(req: InitiateTransferRequest, uetr: Uetr): String =
    val mt103Text = SwiftMt103Builder.build(req, uetr)
    s"""{"rail":"SWIFT_MT103","uetr":"${uetr.value}","mt103_message":${jsonString(mt103Text)},"idempotency_key":"${req.idempotencyKey}"}"""

  private def buildPacs008Payload(req: InitiateTransferRequest, uetr: Uetr): String =
    val pacs008Xml = SwiftPacs008Builder.build(req, uetr)
    s"""{"rail":"SWIFT_PACS008","uetr":"${uetr.value}","pacs008_xml":${jsonString(pacs008Xml)},"idempotency_key":"${req.idempotencyKey}"}"""

  private def jsonString(s: String): String =
    "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\""

  // ── HTTP helpers ─────────────────────────────────────────────────────────────

  private def postJson(path: String, json: String): String =
    val req = JHttpRequest.newBuilder(URI.create(s"${config.aggregatorUrl}$path"))
      .header("Authorization", s"Bearer ${config.apiKey}")
      .header("Content-Type", "application/json")
      .POST(JHttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
      .timeout(Duration.ofSeconds(30))
      .build()
    checkResponse(http.send(req, BodyHandlers.ofString(StandardCharsets.UTF_8)))

  private def getJson(path: String): String =
    val req = JHttpRequest.newBuilder(URI.create(s"${config.aggregatorUrl}$path"))
      .header("Authorization", s"Bearer ${config.apiKey}")
      .GET()
      .timeout(Duration.ofSeconds(30))
      .build()
    checkResponse(http.send(req, BodyHandlers.ofString(StandardCharsets.UTF_8)))

  private def checkResponse(resp: JHttpResponse[String]): String =
    if resp.statusCode() >= 400 then
      throw new RuntimeException(s"SWIFT aggregator API error ${resp.statusCode()}: ${resp.body()}")
    resp.body()

  // ── JSON helpers (no external dependency) ────────────────────────────────────

  private def extractField(json: String, key: String): Option[String] =
    s""""$key"\\s*:\\s*"([^"\\\\]*)"""".r.findFirstMatchIn(json).map(_.group(1))

  private def parseTransferFromJson(body: String, fallbackId: TransferId): BankTransfer =
    val id       = extractField(body, "id").orElse(extractField(body, "transfer_id")).getOrElse(fallbackId.value)
    val uetrStr  = extractField(body, "uetr")
    val statusStr = extractField(body, "status").getOrElse("pending")
    val status   = statusStr.toLowerCase match
      case "settled" | "completed" | "succeeded" => BankTransferStatus.Settled
      case "rejected" =>
        val code = extractField(body, "reason_code").getOrElse("RJCT")
        val desc = extractField(body, "reason_desc").getOrElse("")
        BankTransferStatus.Rejected(RejectCode(code), desc)
      case "canceled" => BankTransferStatus.Canceled
      case _          => BankTransferStatus.Pending
    val amountStr = extractField(body, "amount").getOrElse("0")
    val ccyStr    = extractField(body, "currency").getOrElse("USD")
    val dummy     = BankAccount(holderName = "", countryCode = "")
    BankTransfer(
      id           = TransferId(id),
      rail         = RailKind.SWIFT_PACS008,
      amount       = parseMoneyDecimal(amountStr, ccyStr),
      sender       = dummy,
      recipient    = dummy,
      reference    = extractField(body, "reference").getOrElse(""),
      status       = status,
      createdAt    = Instant.now(),
      uetr         = uetrStr.map(Uetr(_)),
    )

  private def parseMoneyDecimal(decStr: String, ccyCode: String): Money =
    val ccy   = Currency(ccyCode.toUpperCase)
    val power = Currency.minorUnitsPower(ccy)
    val bd    = scala.util.Try(BigDecimal(decStr)).getOrElse(BigDecimal(0))
    val minor = (bd * BigDecimal(math.pow(10, power).toLong)).toLong
    Money(minor, ccy)


/** SWIFT adapter configuration. */
case class SwiftConfig(
  aggregatorUrl: String,  // e.g. "https://api.currencycloud.com/v2"
  apiKey:        String,  // Bearer token for aggregator REST API
  defaultCharge: ChargeBearer = ChargeBearer.SHA,
)

object SwiftConfig:
  /** Load config from environment variables.
   *  SWIFT_AGGREGATOR_URL, SWIFT_API_KEY, SWIFT_DEFAULT_CHARGE (OUR/SHA/BEN) */
  def fromEnv: SwiftConfig =
    val chargeStr = sys.env.getOrElse("SWIFT_DEFAULT_CHARGE", "SHA").toUpperCase
    val charge    = chargeStr match
      case "OUR" => ChargeBearer.OUR
      case "BEN" => ChargeBearer.BEN
      case _     => ChargeBearer.SHA
    SwiftConfig(
      aggregatorUrl = sys.env.getOrElse("SWIFT_AGGREGATOR_URL", "https://api.currencycloud.com/v2"),
      apiKey        = sys.env.getOrElse("SWIFT_API_KEY", ""),
      defaultCharge = charge,
    )
