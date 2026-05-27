package scalascript.payments.aunpp

import java.net.URI
import java.net.http.{HttpClient, HttpRequest as JHttpRequest, HttpResponse as JHttpResponse}
import java.net.http.HttpResponse.BodyHandlers
import java.nio.charset.StandardCharsets
import java.time.Duration

/** Australia NPP (New Payments Platform) REST client.
 *
 *  Wraps three operations:
 *  1. PayID resolution  — resolves a PayID (mobile / email / ABN) to BSB + account number
 *     via the NPP participant gateway's PayID resolution endpoint.
 *  2. Payment initiation — submits an NPP credit transfer (ISO 20022 pacs.008) to the
 *     aggregator REST endpoint.
 *  3. Payment status    — polls the aggregator for the current transfer state.
 *
 *  Auth: API key via `Authorization: Bearer <apiKey>` on every request.
 *  Wire format: JSON to a NPP participant / aggregator (e.g. ANZ, CBA, Monoova, Zepto).
 *
 *  PayID format: `mobile` (e.g. `+61412345678`), `email` (e.g. `user@example.com`),
 *  or `abn` (Australian Business Number, e.g. `51 824 753 556`).
 *
 *  See docs/payment-rails-apac.md §AU_NPP.
 */
class AuNppApi(config: AuNppConfig):

  private val http: HttpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(10))
    .build()

  // ── PayID resolution ───────────────────────────────────────────────────────

  /** Resolve a PayID proxy to its registered BSB and account number.
   *
   *  @param payid  the PayID value (mobile number, email address, or ABN)
   *  @return       (bsb, accountNumber) tuple
   */
  def resolvePayId(payid: String): (String, String) =
    val json =
      s"""{
         |  "payid": "${escJson(payid)}"
         |}""".stripMargin

    val body = postJson("/v1/payid/resolve", json)
    val bsb  = extractField(body, "bsb")
                 .orElse(extractField(body, "bsbNumber"))
                 .getOrElse(
                   throw new RuntimeException(s"PayID resolution response missing 'bsb' field: $body")
                 )
    val acct = extractField(body, "accountNumber")
                 .orElse(extractField(body, "account"))
                 .getOrElse(
                   throw new RuntimeException(s"PayID resolution response missing 'accountNumber' field: $body")
                 )
    (bsb, acct)

  // ── Payment initiation ─────────────────────────────────────────────────────

  /** Submit an NPP credit transfer as ISO 20022 pacs.008 payload to the aggregator.
   *
   *  @param endToEndId      idempotency key / end-to-end reference (max 35 chars)
   *  @param amountCents     amount in AUD cents (integer)
   *  @param creditorBsb     creditor BSB number (6 digits, e.g. "062-000")
   *  @param creditorAccount creditor account number
   *  @param creditorName    creditor holder name
   *  @param debtorBsb       debtor BSB number
   *  @param debtorAccount   debtor account number
   *  @param debtorName      debtor holder name
   *  @param reference       payment description / end-to-end reference shown to recipient
   *  @param payidUsed       optional PayID value that was resolved (for audit/logging)
   *  @return                raw JSON response body from the aggregator
   */
  def sendNppPayment(
      endToEndId:      String,
      amountCents:     Long,
      creditorBsb:     String,
      creditorAccount: String,
      creditorName:    String,
      debtorBsb:       String,
      debtorAccount:   String,
      debtorName:      String,
      reference:       String,
      payidUsed:       Option[String] = None,
  ): String =
    val payidField = payidUsed.fold("") { p =>
      s""",
         |  "payidUsed": "${escJson(p)}"""".stripMargin
    }
    // ISO 20022 pacs.008-shaped JSON — aggregator maps to full XML internally
    val json =
      s"""{
         |  "msgId": "${escJson(endToEndId)}",
         |  "endToEndId": "${escJson(endToEndId.take(35))}",
         |  "instructedAmount": {
         |    "currency": "AUD",
         |    "value": $amountCents
         |  },
         |  "creditor": {
         |    "name": "${escJson(creditorName)}",
         |    "bsb": "${escJson(creditorBsb)}",
         |    "accountNumber": "${escJson(creditorAccount)}"
         |  },
         |  "debtor": {
         |    "name": "${escJson(debtorName)}",
         |    "bsb": "${escJson(debtorBsb)}",
         |    "accountNumber": "${escJson(debtorAccount)}"
         |  },
         |  "remittanceInfo": "${escJson(reference)}",
         |  "serviceLevel": "NPP"$payidField
         |}""".stripMargin

    postJson("/v1/npp/payments", json)

  // ── Payment status ─────────────────────────────────────────────────────────

  /** Poll the aggregator for the current status of an NPP transfer.
   *
   *  @param id  the transfer ID returned in the initiation response
   *  @return    raw JSON response body
   */
  def getPaymentStatus(id: String): String =
    getJson(s"/v1/npp/payments/$id")

  // ── HTTP helpers ───────────────────────────────────────────────────────────

  private[aunpp] def postJson(path: String, json: String): String =
    val req = JHttpRequest.newBuilder(URI.create(s"${config.baseUrl}$path"))
      .header("Authorization", s"Bearer ${config.apiKey}")
      .header("Content-Type", "application/json")
      .header("Accept", "application/json")
      .POST(JHttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
      .timeout(Duration.ofSeconds(30))
      .build()
    checkResponse(http.send(req, BodyHandlers.ofString(StandardCharsets.UTF_8)))

  private[aunpp] def getJson(path: String): String =
    val req = JHttpRequest.newBuilder(URI.create(s"${config.baseUrl}$path"))
      .header("Authorization", s"Bearer ${config.apiKey}")
      .GET()
      .timeout(Duration.ofSeconds(30))
      .build()
    checkResponse(http.send(req, BodyHandlers.ofString(StandardCharsets.UTF_8)))

  private def checkResponse(resp: JHttpResponse[String]): String =
    if resp.statusCode() >= 400 then
      throw new RuntimeException(s"NPP aggregator API error ${resp.statusCode()}: ${resp.body()}")
    resp.body()

  // ── JSON parsing helpers ───────────────────────────────────────────────────

  private[aunpp] def extractField(json: String, key: String): Option[String] =
    s""""$key"\\s*:\\s*"([^"\\\\]*)"""".r.findFirstMatchIn(json).map(_.group(1))

  private[aunpp] def extractBoolField(json: String, key: String): Option[Boolean] =
    s""""$key"\\s*:\\s*(true|false)""".r.findFirstMatchIn(json).map(_.group(1) == "true")

  private def escJson(s: String): String =
    s.replace("\\", "\\\\").replace("\"", "\\\"")


/** Australia NPP adapter configuration.
 *
 *  @param apiKey         Bearer token for the NPP aggregator API
 *  @param baseUrl        aggregator base URL (e.g. "https://api.npp-gateway.example.com")
 *  @param webhookSecret  shared secret for HMAC-SHA256 webhook signature verification
 */
case class AuNppConfig(
  apiKey:        String,
  baseUrl:       String,
  webhookSecret: String,
)

object AuNppConfig:
  /** Load config from environment variables.
   *  AU_NPP_API_KEY, AU_NPP_BASE_URL, AU_NPP_WEBHOOK_SECRET */
  def fromEnv: AuNppConfig =
    AuNppConfig(
      apiKey        = sys.env.getOrElse("AU_NPP_API_KEY",        ""),
      baseUrl       = sys.env.getOrElse("AU_NPP_BASE_URL",       "https://api.npp-gateway.example.com"),
      webhookSecret = sys.env.getOrElse("AU_NPP_WEBHOOK_SECRET", ""),
    )
