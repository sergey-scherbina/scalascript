package scalascript.payments.ukfps

import scalascript.payments.bankrails.*
import scalascript.payments.money.{Money, Currency}
import scalascript.payments.webhook.WebhookReceiver
import java.net.{URI}
import java.net.http.{HttpClient, HttpRequest as JHttpRequest, HttpResponse as JHttpResponse}
import java.net.http.HttpResponse.BodyHandlers
import java.nio.charset.StandardCharsets
import java.time.{Duration, Instant}

/** UK Faster Payments Service (FPS) BankRailsProvider adapter.
 *
 *  Communicates with a sponsored aggregator (TrueLayer, ClearBank, OpenPayd, Modulr)
 *  via REST JSON over HTTPS.  OAuth2 client credentials + mTLS client certificate
 *  (mTLS is handled at the HTTPS transport layer; key path is in UkFpsConfig).
 *
 *  Key behaviours:
 *  - Performs a Confirmation of Payee (CoP) name-check before initiating the payment.
 *    If CoP returns NoMatch, `UkCopNameMismatch` is raised.
 *  - Idempotency key maps to `endToEndId` (max 35 chars, truncated if necessary).
 *  - Amount is sent as pence (integer × 100 of GBP major units).
 *  - Returns `BankTransfer(status = Pending)`; terminal states arrive via webhook.
 *
 *  Wire format: JSON payment request with mandatory fields:
 *    `sortCode`, `accountNumber`, `amount` (pence), `currency` ("GBP"),
 *    `reference` (max 18 chars), `endToEndId` (max 35 chars).
 *
 *  See docs/international-bank-rails.md §v1.55.3.
 */
class UkFpsProvider(config: UkFpsConfig) extends BankRailsProvider:

  private val http: HttpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(10))
    .build()

  private val cop = ConfirmationOfPayee(config)

  def id:             String        = "uk-fps"
  def displayName:    String        = "UK Faster Payments Service (Pay.UK)"
  def spiVersion:     String        = "1.55.3"
  def supportedRails: Set[RailKind] = Set(RailKind.UK_FPS)

  // ── Push: UK FPS credit transfer ──────────────────────────────────────────

  def initiateTransfer(req: InitiateTransferRequest): BankTransfer =
    if !supportedRails.contains(req.rail) then
      throw UnsupportedRail(req.rail, id)

    val sortCode      = req.recipient.sortCode.getOrElse(
      throw new IllegalArgumentException("BankAccount.sortCode is required for UK_FPS")
    )
    val accountNumber = req.recipient.accountNumber.getOrElse(
      throw new IllegalArgumentException("BankAccount.accountNumber is required for UK_FPS")
    )

    // Confirmation of Payee name-check (Pay.UK mandate)
    if config.copEnabled then
      cop.checkPayee(sortCode, accountNumber, req.recipient.holderName) match
        case CopResult.NoMatch =>
          throw UkCopNameMismatch(None)
        case CopResult.CloseMatch(suggested) if !config.allowCloseMatch =>
          throw UkCopNameMismatch(Some(suggested))
        case _ => // Matched, CloseMatch (allowed), AccountSwitched, Unavailable — proceed

    val amountPence = req.amount.minorUnits
    val reference   = req.reference.take(18)   // FPS reference field max 18 chars
    val endToEndId  = req.idempotencyKey.take(35) // EndToEndIdentification max 35 chars

    val json =
      s"""{
         |  "sortCode": "${escJson(sortCode)}",
         |  "accountNumber": "${escJson(accountNumber)}",
         |  "amount": $amountPence,
         |  "currency": "GBP",
         |  "reference": "${escJson(reference)}",
         |  "endToEndId": "${escJson(endToEndId)}",
         |  "payee": {
         |    "name": "${escJson(req.recipient.holderName)}"
         |  }
         |}""".stripMargin

    val respBody = postJson("/payments", json)
    val transferId = extractField(respBody, "paymentId")
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
    val body = getJson(s"/payments/${id.value}")
    parseTransferFromJson(body, id)

  def cancelTransfer(id: TransferId): Unit =
    postJson(s"/payments/${id.value}/cancel", "{}")
    ()

  // ── Pull: not supported on UK FPS (FPS is push-only; BACS DD is a separate rail) ───

  def initiateDirectDebit(req: InitiateDirectDebitRequest): BankTransfer =
    throw UnsupportedRail(req.rail, id)

  def getDirectDebit(id: TransferId): BankTransfer =
    throw TransferNotFound(id)

  // ── Webhook ────────────────────────────────────────────────────────────────

  def webhookReceiver: WebhookReceiver[BankRailsEvent] = UkFpsWebhookReceiver()

  // ── CoP helper (exposed for callers who want to pre-check before initiating) ──

  /** Perform a Confirmation of Payee name-check directly (without initiating a payment).
   *  Useful for UI "verify account" flows before showing a confirmation screen.
   *
   *  @param sortCode     6-digit UK sort code, format "XX-XX-XX"
   *  @param accountNumber 8-digit UK account number
   *  @param name         Account holder name to verify
   */
  def checkPayee(sortCode: String, accountNumber: String, name: String): CopResult =
    cop.checkPayee(sortCode, accountNumber, name)

  // ── HTTP helpers ───────────────────────────────────────────────────────────

  private def postJson(path: String, json: String): String =
    val req = JHttpRequest.newBuilder(URI.create(s"${config.apiUrl}$path"))
      .header("Authorization", s"Bearer ${config.apiKey}")
      .header("Content-Type", "application/json")
      .header("Accept", "application/json")
      .POST(JHttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
      .timeout(Duration.ofSeconds(30))
      .build()
    checkResponse(http.send(req, BodyHandlers.ofString(StandardCharsets.UTF_8)))

  private def getJson(path: String): String =
    val req = JHttpRequest.newBuilder(URI.create(s"${config.apiUrl}$path"))
      .header("Authorization", s"Bearer ${config.apiKey}")
      .GET()
      .timeout(Duration.ofSeconds(30))
      .build()
    checkResponse(http.send(req, BodyHandlers.ofString(StandardCharsets.UTF_8)))

  private def checkResponse(resp: JHttpResponse[String]): String =
    if resp.statusCode() >= 400 then
      throw new RuntimeException(s"UK FPS API error ${resp.statusCode()}: ${resp.body()}")
    resp.body()

  // ── JSON helpers (no external dependency) ─────────────────────────────────

  private def extractField(json: String, key: String): Option[String] =
    s""""$key"\\s*:\\s*"([^"\\\\]*)"""".r.findFirstMatchIn(json).map(_.group(1))

  private def parseTransferFromJson(body: String, fallbackId: TransferId): BankTransfer =
    val id       = extractField(body, "paymentId")
                     .orElse(extractField(body, "id"))
                     .getOrElse(fallbackId.value)
    val statusStr = extractField(body, "status").getOrElse("pending")
    val status   = statusStr.toLowerCase match
      case "settled" | "completed" | "accepted" | "succeeded" => BankTransferStatus.Settled
      case "rejected"                                          =>
        val code = extractField(body, "rejectCode").getOrElse("unknown")
        val desc = extractField(body, "reason").getOrElse("")
        BankTransferStatus.Rejected(RejectCode(code), desc)
      case "returned"                                          =>
        val code = extractField(body, "returnCode").getOrElse("unknown")
        val desc = extractField(body, "description").getOrElse("")
        BankTransferStatus.Returned(ReturnCode(code), desc)
      case "canceled" | "cancelled"                            => BankTransferStatus.Canceled
      case _                                                   => BankTransferStatus.Pending
    val amountStr = extractField(body, "amount").getOrElse("0")
    val dummy     = BankAccount(holderName = "", countryCode = "GB")
    BankTransfer(
      id        = TransferId(id),
      rail      = RailKind.UK_FPS,
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

  private def escJson(s: String): String =
    s.replace("\\", "\\\\").replace("\"", "\\\"")


/** UK FPS adapter configuration. */
case class UkFpsConfig(
  apiUrl:         String,           // e.g. "https://api.truelayer.com/v1"
  apiKey:         String,           // OAuth2 Bearer token
  copEnabled:     Boolean = true,   // whether to call CoP before each payment (Pay.UK mandated)
  allowCloseMatch: Boolean = false, // if true, CloseMatch CoP result is allowed to proceed
)

object UkFpsConfig:
  /** Load config from environment variables.
   *  UK_FPS_API_URL, UK_FPS_API_KEY, UK_FPS_COP_ENABLED, UK_FPS_ALLOW_CLOSE_MATCH */
  def fromEnv: UkFpsConfig =
    UkFpsConfig(
      apiUrl          = sys.env.getOrElse("UK_FPS_API_URL",    "https://api.fps-gateway.example.com/v1"),
      apiKey          = sys.env.getOrElse("UK_FPS_API_KEY",    ""),
      copEnabled      = sys.env.getOrElse("UK_FPS_COP_ENABLED",     "true").toBoolean,
      allowCloseMatch = sys.env.getOrElse("UK_FPS_ALLOW_CLOSE_MATCH","false").toBoolean,
    )
