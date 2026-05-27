package scalascript.payments.mxspei

import java.net.URI
import java.net.http.{HttpClient, HttpRequest as JHttpRequest, HttpResponse as JHttpResponse}
import java.net.http.HttpResponse.BodyHandlers
import java.nio.charset.StandardCharsets
import java.time.Duration
import scalascript.payments.bankrails.*
import scalascript.payments.money.{Money, Currency}
import java.time.Instant

/** STP/aggregator REST client for Mexico SPEI transfers.
 *
 *  Wraps two operations:
 *  1. Submit transfer — POST to aggregator REST API (STP or Conekta)
 *  2. Get transfer status — GET by transfer ID
 *
 *  Auth: Bearer token (`apiKey`) on every request.
 *  Wire format: JSON to a BANXICO-connected aggregator.
 *
 *  See docs/payment-rails-apac.md §MX_SPEI.
 */
class MxSpeiApi(config: MxSpeiConfig):

  private val http: HttpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(10))
    .build()

  /** Submit a SPEI credit transfer.
   *
   *  @param transfer  the transfer request to submit
   *  @return          the submitted BankTransfer with Pending status
   */
  def submitTransfer(transfer: InitiateTransferRequest): BankTransfer =
    val clabe  = transfer.recipient.clabe.getOrElse("")
    val amount = transfer.amount.minorUnits
    val json   =
      s"""{
         |  "clabe": "${escJson(clabe)}",
         |  "amount": $amount,
         |  "currency": "MXN",
         |  "reference": "${escJson(transfer.reference)}",
         |  "concept": "${escJson(transfer.reference)}",
         |  "idempotencyKey": "${escJson(transfer.idempotencyKey)}",
         |  "bankCode": "${escJson(config.bankCode)}"
         |}""".stripMargin
    val body = postJson("/spei/transfers", json)
    val id   = extractField(body, "id")
                .orElse(extractField(body, "transactionId"))
                .orElse(extractField(body, "referenceNumber"))
                .getOrElse(transfer.idempotencyKey)
    BankTransfer(
      id        = TransferId(id),
      rail      = RailKind.MX_SPEI,
      amount    = transfer.amount,
      sender    = transfer.sender,
      recipient = transfer.recipient,
      reference = transfer.reference,
      status    = BankTransferStatus.Pending,
      createdAt = Instant.now(),
      metadata  = transfer.metadata,
    )

  /** Get the current status of a SPEI transfer.
   *
   *  @param id  the transfer ID returned by submitTransfer
   *  @return    the BankTransfer with current status
   */
  def getTransferStatus(id: TransferId): BankTransfer =
    val body   = getJson(s"/spei/transfers/${id.value}")
    val idVal  = extractField(body, "id")
                  .orElse(extractField(body, "transactionId"))
                  .getOrElse(id.value)
    val status = parseStatus(body)
    val dummy  = BankAccount(holderName = "", countryCode = "MX")
    BankTransfer(
      id        = TransferId(idVal),
      rail      = RailKind.MX_SPEI,
      amount    = parseMoney(body),
      sender    = dummy,
      recipient = dummy,
      reference = extractField(body, "reference").getOrElse(""),
      status    = status,
      createdAt = Instant.now(),
    )

  // ── HTTP helpers ───────────────────────────────────────────────────────────

  private[mxspei] def postJson(path: String, json: String): String =
    val req = JHttpRequest.newBuilder(URI.create(s"${config.baseUrl}$path"))
      .header("Authorization", s"Bearer ${config.apiKey}")
      .header("Content-Type", "application/json")
      .header("Accept", "application/json")
      .POST(JHttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
      .timeout(Duration.ofSeconds(30))
      .build()
    checkResponse(http.send(req, BodyHandlers.ofString(StandardCharsets.UTF_8)))

  private[mxspei] def getJson(path: String): String =
    val req = JHttpRequest.newBuilder(URI.create(s"${config.baseUrl}$path"))
      .header("Authorization", s"Bearer ${config.apiKey}")
      .GET()
      .timeout(Duration.ofSeconds(30))
      .build()
    checkResponse(http.send(req, BodyHandlers.ofString(StandardCharsets.UTF_8)))

  private def checkResponse(resp: JHttpResponse[String]): String =
    if resp.statusCode() >= 400 then
      throw new RuntimeException(s"SPEI aggregator API error ${resp.statusCode()}: ${resp.body()}")
    resp.body()

  // ── JSON parsing helpers ───────────────────────────────────────────────────

  private[mxspei] def extractField(json: String, key: String): Option[String] =
    s""""$key"\\s*:\\s*"([^"\\\\]*)"""".r.findFirstMatchIn(json).map(_.group(1))

  private[mxspei] def extractNumField(json: String, key: String): Option[String] =
    s""""$key"\\s*:\\s*(\\d+)""".r.findFirstMatchIn(json).map(_.group(1))

  private[mxspei] def parseStatus(body: String): BankTransferStatus =
    extractField(body, "status").getOrElse("pending").toLowerCase match
      case "confirmed" | "settled" | "completed" | "acreditado" => BankTransferStatus.Settled
      case "rejected"  | "rechazado" =>
        val code = extractField(body, "errorCode").getOrElse("rejected")
        val desc = extractField(body, "errorMessage").getOrElse("rejected")
        BankTransferStatus.Rejected(RejectCode(code), desc)
      case "returned"  | "devuelto" =>
        val code = extractField(body, "returnCode").getOrElse("returned")
        val desc = extractField(body, "returnReason").getOrElse("")
        BankTransferStatus.Returned(ReturnCode(code), desc)
      case _                        => BankTransferStatus.Pending

  private[mxspei] def parseMoney(body: String): Money =
    val units = extractNumField(body, "amount").getOrElse("0")
    Money(scala.util.Try(units.toLong).getOrElse(0L), Currency("MXN"))

  private def escJson(s: String): String =
    s.replace("\\", "\\\\").replace("\"", "\\\"")


/** Mexico SPEI adapter configuration.
 *
 *  @param apiKey         Bearer token for the STP/aggregator API
 *  @param baseUrl        aggregator base URL (e.g. "https://api.stpmex.com/v1")
 *  @param webhookSecret  HMAC-SHA256 secret for X-SPEI-Signature verification
 *  @param bankCode       sending institution CLABE bank code (3 digits, e.g. "646")
 */
case class MxSpeiConfig(
  apiKey:        String,
  baseUrl:       String,
  webhookSecret: String,
  bankCode:      String,
)

object MxSpeiConfig:
  /** Load config from environment variables.
   *  MX_SPEI_API_KEY, MX_SPEI_BASE_URL, MX_SPEI_WEBHOOK_SECRET, MX_SPEI_BANK_CODE */
  def fromEnv: MxSpeiConfig =
    MxSpeiConfig(
      apiKey        = sys.env.getOrElse("MX_SPEI_API_KEY",        ""),
      baseUrl       = sys.env.getOrElse("MX_SPEI_BASE_URL",       "https://api.stpmex.com/v1"),
      webhookSecret = sys.env.getOrElse("MX_SPEI_WEBHOOK_SECRET", ""),
      bankCode      = sys.env.getOrElse("MX_SPEI_BANK_CODE",      "646"),
    )


/** CLABE (Clave Bancaria Estandarizada) validator.
 *
 *  CLABE is an 18-digit banking standard used for SPEI transfers in Mexico.
 *  The 18th digit is a control digit computed by applying alternating
 *  multipliers [3, 7, 1, 3, 7, 1, ...] to the first 17 digits,
 *  summing the weighted units digits, and computing (10 - sum % 10) % 10.
 */
object ClabeValidator:

  private val Multipliers = Array(3, 7, 1, 3, 7, 1, 3, 7, 1, 3, 7, 1, 3, 7, 1, 3, 7)

  /** Validate a CLABE number.
   *
   *  @param clabe  the CLABE string to validate
   *  @return       Right(clabe) if valid; Left(error message) if invalid
   */
  def validate(clabe: String): Either[String, String] =
    if clabe.length != 18 then
      return Left(s"CLABE must be 18 digits, got ${clabe.length}: $clabe")
    if !clabe.forall(_.isDigit) then
      return Left(s"CLABE must contain only digits: $clabe")

    val digits   = clabe.map(_.asDigit)
    val weighted = Multipliers.indices.foldLeft(0) { (sum, i) =>
      sum + (digits(i) * Multipliers(i)) % 10
    }
    val expected = (10 - (weighted % 10)) % 10
    val actual   = digits(17)

    if actual != expected then
      Left(s"CLABE check digit invalid: expected $expected, got $actual in $clabe")
    else
      Right(clabe)
