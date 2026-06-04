package scalascript.payments.sepa

import scalascript.payments.bankrails.*
import scalascript.payments.money.{Money, Currency}
import scalascript.payments.webhook.WebhookReceiver
import java.net.{URI}
import java.net.http.{HttpClient, HttpRequest as JHttpRequest, HttpResponse as JHttpResponse}
import java.net.http.HttpResponse.BodyHandlers
import java.nio.charset.StandardCharsets
import java.time.{Duration, Instant}

/** SEPA BankRailsProvider adapter.
 *
 *  Implements SEPA Credit Transfer (PAIN.001), SEPA Core Direct Debit (PAIN.008),
 *  and SEPA Instant Credit Transfer / SCT Inst (pacs.008.001.08) via an aggregator
 *  REST API.  Signature auth via Bearer token.
 *
 *  SCT Inst (RailKind.SCT_INST) uses the same aggregator endpoint as SEPA CT but
 *  submits a pacs.008 pacs message with LclInstrm=INST and SttlmMtd=CLRG/SCTInst.
 *  The aggregator routes the message to TIPS (ECB) or RT1 (EBA CLEARING).
 *
 *  Note: EU Regulation 2024/886 mandates that all eurozone PSPs offering SEPA CT
 *  must also offer SCT Inst at no extra charge (mandatory since March 2024).
 *
 *  Configuration via SepaConfig (or environment variables at construction time).
 *
 *  See docs/specs/bank-rails.md §8 v1.54.1 and docs/specs/international-bank-rails.md §v1.55.2.
 */
class SepaProvider(config: SepaConfig) extends BankRailsProvider:

  private val http = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(10))
    .build()

  def id:             String       = "sepa"
  def displayName:    String       = "SEPA (Credit Transfer + Core Direct Debit + SCT Inst)"
  def spiVersion:     String       = "1.55.2"
  // SCT_INST added v1.55.2: same aggregator as CT, routed to TIPS/RT1 via pacs.008 INST
  def supportedRails: Set[RailKind] = Set(RailKind.SEPA_CT, RailKind.SEPA_DD, RailKind.SCT_INST)

  // ── Push: SEPA Credit Transfer + SCT Inst ─────────────────────────────────

  def initiateTransfer(req: InitiateTransferRequest): BankTransfer =
    if !supportedRails.contains(req.rail) then
      throw UnsupportedRail(req.rail, id)
    // SCT Inst uses pacs.008 with INST local instrument; CT uses pain.001
    val painXml = req.rail match
      case RailKind.SCT_INST => SepaPainXml.buildSctInstPacs008(req)
      case _                 => SepaPainXml.buildPain001(req)
    val respBody = postXml("/transfers", painXml)
    // Parse transfer ID from response; fall back to idempotency key if not present
    val transferId = extractField(respBody, "transfer_id")
                       .orElse(extractField(respBody, "id"))
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
    val body = getJson(s"/transfers/${id.value}")
    parseTransferFromJson(body, id)

  def cancelTransfer(id: TransferId): Unit =
    postJson(s"/transfers/${id.value}/cancel", "{}")
    ()

  // ── Pull: SEPA Core Direct Debit ───────────────────────────────────────────

  def initiateDirectDebit(req: InitiateDirectDebitRequest): BankTransfer =
    if req.rail != RailKind.SEPA_DD then throw UnsupportedRail(req.rail, id)
    val mandate = DirectDebitMandate(
      id              = req.mandateId,
      rail            = RailKind.SEPA_DD,
      debtorAccount   = req.debtorAccount,
      creditorAccount = req.creditorAccount,
      creditorName    = req.creditorName,
      status          = MandateStatus.Active,
      sequenceType    = MandateSequenceType.Recurring,
    )
    val painXml = SepaPainXml.buildPain008(req, mandate)
    val respBody = postXml("/direct-debits", painXml)
    val transferId = extractField(respBody, "transfer_id")
                       .orElse(extractField(respBody, "id"))
                       .getOrElse(req.idempotencyKey)
    BankTransfer(
      id        = TransferId(transferId),
      rail      = req.rail,
      amount    = req.amount,
      sender    = req.debtorAccount,
      recipient = req.creditorAccount,
      reference = req.reference,
      status    = BankTransferStatus.Pending,
      createdAt = Instant.now(),
      metadata  = req.metadata,
    )

  def getDirectDebit(id: TransferId): BankTransfer =
    val body = getJson(s"/direct-debits/${id.value}")
    parseTransferFromJson(body, id)

  // ── Webhook ────────────────────────────────────────────────────────────────

  def webhookReceiver: WebhookReceiver[BankRailsEvent] = SepaWebhookReceiver()

  // ── HTTP helpers ───────────────────────────────────────────────────────────

  /** POST raw XML to the SEPA aggregator REST endpoint. Returns response body. */
  private def postXml(path: String, xml: String): String =
    val req = JHttpRequest.newBuilder(URI.create(s"${config.apiUrl}$path"))
      .header("Authorization", s"Bearer ${config.apiKey}")
      .header("Content-Type", "application/xml; charset=UTF-8")
      .header("Accept", "application/json")
      .POST(JHttpRequest.BodyPublishers.ofString(xml, StandardCharsets.UTF_8))
      .timeout(Duration.ofSeconds(30))
      .build()
    checkResponse(http.send(req, BodyHandlers.ofString(StandardCharsets.UTF_8)))

  /** POST JSON to the SEPA aggregator REST endpoint. Returns response body. */
  private def postJson(path: String, json: String): String =
    val req = JHttpRequest.newBuilder(URI.create(s"${config.apiUrl}$path"))
      .header("Authorization", s"Bearer ${config.apiKey}")
      .header("Content-Type", "application/json")
      .POST(JHttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
      .timeout(Duration.ofSeconds(30))
      .build()
    checkResponse(http.send(req, BodyHandlers.ofString(StandardCharsets.UTF_8)))

  /** GET JSON from the SEPA aggregator REST endpoint. */
  private def getJson(path: String): String =
    val req = JHttpRequest.newBuilder(URI.create(s"${config.apiUrl}$path"))
      .header("Authorization", s"Bearer ${config.apiKey}")
      .GET()
      .timeout(Duration.ofSeconds(30))
      .build()
    checkResponse(http.send(req, BodyHandlers.ofString(StandardCharsets.UTF_8)))

  private def checkResponse(resp: JHttpResponse[String]): String =
    if resp.statusCode() >= 400 then
      throw new RuntimeException(s"SEPA API error ${resp.statusCode()}: ${resp.body()}")
    resp.body()

  // ── JSON helpers (no external dependency) ─────────────────────────────────

  private def extractField(json: String, key: String): Option[String] =
    s""""$key"\\s*:\\s*"([^"\\\\]*)"""".r.findFirstMatchIn(json).map(_.group(1))

  private def parseTransferFromJson(body: String, fallbackId: TransferId): BankTransfer =
    val id       = extractField(body, "id").orElse(extractField(body, "transfer_id")).getOrElse(fallbackId.value)
    val statusStr = extractField(body, "status").getOrElse("pending")
    val status   = statusStr.toLowerCase match
      case "settled" | "completed" | "succeeded" => BankTransferStatus.Settled
      case "rejected"                             =>
        val code = extractField(body, "reason_code").getOrElse("MS02")
        val desc = extractField(body, "reason_desc").getOrElse("")
        BankTransferStatus.Rejected(RejectCode(code), desc)
      case "returned"                             =>
        val code = extractField(body, "reason_code").getOrElse("MS02")
        val desc = extractField(body, "reason_desc").getOrElse("")
        BankTransferStatus.Returned(ReturnCode(code), desc)
      case "canceled"                             => BankTransferStatus.Canceled
      case _                                      => BankTransferStatus.Pending
    val amountStr = extractField(body, "amount").getOrElse("0")
    val ccyStr    = extractField(body, "currency").getOrElse("EUR")
    val dummy     = BankAccount(iban = None, holderName = "", countryCode = "")
    BankTransfer(
      id        = TransferId(id),
      rail      = RailKind.SEPA_CT,
      amount    = parseMoneyDecimal(amountStr, ccyStr),
      sender    = dummy,
      recipient = dummy,
      reference = extractField(body, "reference").getOrElse(""),
      status    = status,
      createdAt = Instant.now(),
    )

  private def parseMoneyDecimal(decStr: String, ccyCode: String): Money =
    val ccy   = Currency(ccyCode.toUpperCase)
    val power = Currency.minorUnitsPower(ccy)
    val bd    = scala.util.Try(BigDecimal(decStr)).getOrElse(BigDecimal(0))
    val minor = (bd * BigDecimal(math.pow(10, power).toLong)).toLong
    Money(minor, ccy)

/** SEPA adapter configuration.
 *  All fields can be loaded from environment variables (see SepaConfig.fromEnv). */
case class SepaConfig(
  apiUrl:      String,  // e.g. "https://api.yoursepagateway.com/v1"
  apiKey:      String,  // Bearer token for REST API auth
  creditorId:  String,  // SEPA creditor scheme identifier (for DD)
)

object SepaConfig:
  /** Load config from environment variables.
   *  SEPA_API_URL, SEPA_API_KEY, SEPA_CREDITOR_ID */
  def fromEnv: SepaConfig =
    SepaConfig(
      apiUrl     = sys.env.getOrElse("SEPA_API_URL",    "https://api.sepa-gateway.example.com/v1"),
      apiKey     = sys.env.getOrElse("SEPA_API_KEY",    ""),
      creditorId = sys.env.getOrElse("SEPA_CREDITOR_ID",""),
    )
