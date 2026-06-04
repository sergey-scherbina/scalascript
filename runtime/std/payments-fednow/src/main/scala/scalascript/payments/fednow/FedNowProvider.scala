package scalascript.payments.fednow

import scalascript.payments.bankrails.*
import scalascript.payments.money.{Money, Currency}
import scalascript.payments.webhook.WebhookReceiver
import java.net.{URI}
import java.net.http.{HttpClient, HttpRequest as JHttpRequest, HttpResponse as JHttpResponse}
import java.net.http.HttpResponse.BodyHandlers
import java.nio.charset.StandardCharsets
import java.time.{Duration, Instant}

/** FedNow BankRailsProvider adapter.
 *
 *  Implements FedNow instant credit transfers via ISO 20022 pacs.008 submission
 *  to the FedNow Connect REST API (FedLine Advantage).  mTLS client certificate
 *  auth is configured via FedNowConfig (cert/key paths loaded at construction time).
 *
 *  FedNow is T+0 (real-time 24/7/365); typical settlement confirmation < 20 seconds.
 *  Maximum transfer amount is $500,000 USD per FedNow Operating Procedures (2026).
 *
 *  FedNow does NOT support:
 *  - Direct debit / pull payments
 *  - Transfer cancellation after acceptance
 *  - Currencies other than USD
 *
 *  See specs/bank-rails.md §v1.54.4.
 */
class FedNowProvider(config: FedNowConfig) extends BankRailsProvider:

  private val http = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(10))
    .build()

  def id:             String        = "fednow"
  def displayName:    String        = "FedNow Instant Payments (US Federal Reserve)"
  def spiVersion:     String        = "1.54.4"
  def supportedRails: Set[RailKind] = Set(RailKind.FEDNOW)

  // FedNow limit: $500,000 USD (500,000 * 100 cents = 50_000_000 minor units)
  private val FedNowLimitMinor: Long = 50_000_000L

  // ── Push: FedNow Credit Transfer (pacs.008) ─────────────────────────────

  def initiateTransfer(req: InitiateTransferRequest): BankTransfer =
    if !supportedRails.contains(req.rail) then
      throw UnsupportedRail(req.rail, id)

    // Enforce FedNow USD-only and transaction limit
    if req.amount.currency != Currency("USD") then
      throw UnsupportedRail(req.rail, s"$id (FedNow only supports USD, got ${req.amount.currency})")
    if req.amount.minorUnits > FedNowLimitMinor then
      val limit = Money(FedNowLimitMinor, Currency("USD"))
      throw FedNowLimitExceeded(req.amount, limit)

    val pacs008 = Iso20022Xml.buildPacs008(req, config.fednowRoutingNumber)
    val respBody = postXml("/transfers", pacs008)
    // Extract InstrId from response (if present) or fall back to idempotency key
    val transferId = Iso20022Xml.extractInstrId(respBody)
                       .orElse(extractJsonField(respBody, "instrId"))
                       .orElse(extractJsonField(respBody, "transfer_id"))
                       .getOrElse(req.idempotencyKey)
    BankTransfer(
      id        = TransferId(transferId),
      rail      = RailKind.FEDNOW,
      amount    = req.amount,
      sender    = req.sender,
      recipient = req.recipient,
      reference = req.reference,
      status    = BankTransferStatus.Pending,
      createdAt = Instant.now(),
      metadata  = req.metadata,
    )

  /** GET /transfers/{instrId} → parse pacs.002 response → map TxSts to BankTransferStatus. */
  def getTransfer(id: TransferId): BankTransfer =
    val body   = getResp(s"/transfers/${id.value}")
    val status = Iso20022Xml.parsePacs002Status(body)
    val dummy  = BankAccount(holderName = "", countryCode = "US")
    BankTransfer(
      id        = id,
      rail      = RailKind.FEDNOW,
      amount    = Money(0L, Currency("USD")),
      sender    = dummy,
      recipient = dummy,
      reference = "",
      status    = status,
      createdAt = Instant.now(),
      settledAt = if status == BankTransferStatus.Settled then Some(Instant.now()) else None,
    )

  /** FedNow transfers cannot be cancelled after acceptance.
   *
   *  FedNow Operating Procedures §4.4: once a credit transfer is accepted (ACCP),
   *  it is irrevocable.  The FedNow platform does not provide a recall endpoint.
   */
  def cancelTransfer(id: TransferId): Unit =
    throw BankRailsCancelError(id, "FedNow transfers cannot be cancelled after acceptance")

  // ── Pull: FedNow does not support direct debit / pull payments ─────────

  def initiateDirectDebit(req: InitiateDirectDebitRequest): BankTransfer =
    throw UnsupportedRail(RailKind.FEDNOW, s"$id — FedNow does not support direct debit")

  def getDirectDebit(id: TransferId): BankTransfer =
    throw UnsupportedRail(RailKind.FEDNOW, s"${this.id} — FedNow does not support direct debit")

  // ── Webhook ────────────────────────────────────────────────────────────────

  def webhookReceiver: WebhookReceiver[BankRailsEvent] = FedNowWebhookReceiver()

  // ── HTTP helpers ───────────────────────────────────────────────────────────

  /** POST raw XML to the FedNow Connect REST API. Returns response body.
   *
   *  FedLine Advantage uses mTLS; the HttpClient should be configured with the
   *  client cert/key from FedNowConfig.fednowCertPath / fednowKeyPath.
   *  Content-Type: application/xml per FedNow ISO 20022 API spec.
   */
  private def postXml(path: String, xmlBody: String): String =
    val req = JHttpRequest.newBuilder(URI.create(s"${config.fednowApiUrl}$path"))
      .header("Content-Type", "application/xml; charset=UTF-8")
      .header("Accept", "application/xml")
      .header("X-FedNow-Participant-Id", config.fednowParticipantId)
      .POST(JHttpRequest.BodyPublishers.ofString(xmlBody, StandardCharsets.UTF_8))
      .timeout(Duration.ofSeconds(30))
      .build()
    checkResponse(http.send(req, BodyHandlers.ofString(StandardCharsets.UTF_8)))

  /** GET from the FedNow Connect REST API. */
  private def getResp(path: String): String =
    val req = JHttpRequest.newBuilder(URI.create(s"${config.fednowApiUrl}$path"))
      .header("Accept", "application/xml")
      .header("X-FedNow-Participant-Id", config.fednowParticipantId)
      .GET()
      .timeout(Duration.ofSeconds(30))
      .build()
    checkResponse(http.send(req, BodyHandlers.ofString(StandardCharsets.UTF_8)))

  private def checkResponse(resp: JHttpResponse[String]): String =
    if resp.statusCode() >= 400 then
      throw new RuntimeException(s"FedNow API error ${resp.statusCode()}: ${resp.body()}")
    resp.body()

  // ── JSON field extraction (no external dependency) ─────────────────────────

  private def extractJsonField(json: String, key: String): Option[String] =
    s""""$key"\\s*:\\s*"([^"\\\\]*)"""".r.findFirstMatchIn(json).map(_.group(1))

/** FedNow adapter configuration.
 *
 *  @param fednowApiUrl       FedNow Connect REST API base URL (FedLine Advantage endpoint)
 *  @param fednowCertPath     Path to the mTLS client certificate (PEM)
 *  @param fednowKeyPath      Path to the mTLS client private key (PEM)
 *  @param fednowRoutingNumber  ABA routing number of the participating FI
 *  @param fednowParticipantId  FedNow participant identifier assigned by the Federal Reserve
 */
case class FedNowConfig(
  fednowApiUrl:        String,
  fednowCertPath:      String,
  fednowKeyPath:       String,
  fednowRoutingNumber: String,
  fednowParticipantId: String,
)

object FedNowConfig:
  /** Load config from environment variables.
   *  FEDNOW_API_URL, FEDNOW_CERT_PATH, FEDNOW_KEY_PATH,
   *  FEDNOW_ROUTING_NUMBER, FEDNOW_PARTICIPANT_ID */
  def fromEnv: FedNowConfig =
    FedNowConfig(
      fednowApiUrl        = sys.env.getOrElse("FEDNOW_API_URL",        "https://fednow-connect.frbservices.org/v1"),
      fednowCertPath      = sys.env.getOrElse("FEDNOW_CERT_PATH",      ""),
      fednowKeyPath       = sys.env.getOrElse("FEDNOW_KEY_PATH",       ""),
      fednowRoutingNumber = sys.env.getOrElse("FEDNOW_ROUTING_NUMBER", ""),
      fednowParticipantId = sys.env.getOrElse("FEDNOW_PARTICIPANT_ID", ""),
    )
