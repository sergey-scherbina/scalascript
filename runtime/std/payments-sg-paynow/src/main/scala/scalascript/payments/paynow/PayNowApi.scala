package scalascript.payments.paynow

import java.net.URI
import java.net.http.{HttpClient, HttpRequest as JHttpRequest, HttpResponse as JHttpResponse}
import java.net.http.HttpResponse.BodyHandlers
import java.nio.charset.StandardCharsets
import java.time.Duration

/** Low-level aggregator REST client for Singapore PayNow.
 *
 *  Wraps two operations:
 *  1. Proxy resolution  — resolves a proxy (mobile / NRIC / UEN / VPA) to its registered
 *     participant bank code and masked account number via the aggregator's proxy directory.
 *  2. Payment initiation — submits a PayNow credit transfer to the FAST network.
 *
 *  Auth: Bearer token (`apiKey`) on every request.
 *  Wire format: JSON to a MAS-licensed aggregator (NETS, DBS PayNow Business API,
 *  OCBC Business Connect, or MatchMove).
 *
 *  See docs/international-bank-rails.md §v1.56.8.
 */
class PayNowApi(config: PayNowConfig):

  private val http: HttpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(10))
    .build()

  // ── Proxy resolution ───────────────────────────────────────────────────────

  /** Resolve a PayNow proxy to its registered bank details.
   *
   *  @param proxyType  one of "MOBILE", "NRIC", "UEN", "VPA"
   *  @param proxyValue the proxy value (e.g. "+6591234567", "S1234567A", "202012345A")
   *  @return           resolution result indicating registration status and participant code
   */
  def resolveProxy(proxyType: String, proxyValue: String): ProxyResolutionResult =
    val json =
      s"""{
         |  "proxyType": "${escJson(proxyType)}",
         |  "proxyValue": "${escJson(proxyValue)}"
         |}""".stripMargin

    val body = postJson("/paynow/proxy/resolve", json)
    parseProxyResolutionResult(body)

  // ── Payment initiation ─────────────────────────────────────────────────────

  /** Submit a PayNow credit transfer to the FAST network.
   *
   *  @param proxyType       resolved proxy type ("MOBILE" / "NRIC" / "UEN" / "VPA")
   *  @param proxyValue      proxy value (= `BankAccount.paynowProxy`)
   *  @param amountCents     amount in SGD cents (integer)
   *  @param transactionRef  idempotency key / end-to-end reference (max 35 chars)
   *  @param reference       payment reference shown to recipient
   *  @param payerInfo       optional payer display name shown to recipient
   *  @return                raw JSON response body from the aggregator
   */
  def initiatePayment(
      proxyType:      String,
      proxyValue:     String,
      amountCents:    Long,
      transactionRef: String,
      reference:      String,
      payerInfo:      Option[String],
  ): String =
    val payerField = payerInfo.fold("") { name =>
      s""",
         |  "payerInfo": "${escJson(name)}"""".stripMargin
    }
    val json =
      s"""{
         |  "proxyType": "${escJson(proxyType)}",
         |  "proxyValue": "${escJson(proxyValue)}",
         |  "amount": $amountCents,
         |  "currency": "SGD",
         |  "transactionRef": "${escJson(transactionRef.take(35))}",
         |  "reference": "${escJson(reference)}",
         |  "senderUen": "${escJson(config.senderUen)}"$payerField
         |}""".stripMargin

    postJson("/paynow/payments", json)

  // ── HTTP helpers ───────────────────────────────────────────────────────────

  private[paynow] def postJson(path: String, json: String): String =
    val req = JHttpRequest.newBuilder(URI.create(s"${config.baseUrl}$path"))
      .header("Authorization", s"Bearer ${config.apiKey}")
      .header("Content-Type", "application/json")
      .header("Accept", "application/json")
      .POST(JHttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
      .timeout(Duration.ofSeconds(30))
      .build()
    checkResponse(http.send(req, BodyHandlers.ofString(StandardCharsets.UTF_8)))

  private[paynow] def getJson(path: String): String =
    val req = JHttpRequest.newBuilder(URI.create(s"${config.baseUrl}$path"))
      .header("Authorization", s"Bearer ${config.apiKey}")
      .GET()
      .timeout(Duration.ofSeconds(30))
      .build()
    checkResponse(http.send(req, BodyHandlers.ofString(StandardCharsets.UTF_8)))

  private def checkResponse(resp: JHttpResponse[String]): String =
    if resp.statusCode() >= 400 then
      throw new RuntimeException(s"PayNow aggregator API error ${resp.statusCode()}: ${resp.body()}")
    resp.body()

  // ── JSON parsing helpers ───────────────────────────────────────────────────

  private[paynow] def extractField(json: String, key: String): Option[String] =
    s""""$key"\\s*:\\s*"([^"\\\\]*)"""".r.findFirstMatchIn(json).map(_.group(1))

  private[paynow] def extractBoolField(json: String, key: String): Option[Boolean] =
    s""""$key"\\s*:\\s*(true|false)""".r.findFirstMatchIn(json).map(_.group(1) == "true")

  private[paynow] def parseProxyResolutionResult(body: String): ProxyResolutionResult =
    val registered      = extractBoolField(body, "registered").getOrElse(false)
    val participantCode = extractField(body, "participantCode")
                           .orElse(extractField(body, "bankCode"))
    val maskedAccount   = extractField(body, "maskedAccountNumber")
                           .orElse(extractField(body, "maskedAccount"))
    ProxyResolutionResult(
      registered      = registered,
      participantCode = participantCode,
      maskedAccount   = maskedAccount,
    )

  private def escJson(s: String): String =
    s.replace("\\", "\\\\").replace("\"", "\\\"")


/** Result of a PayNow proxy resolution call.
 *
 *  @param registered       true if the proxy is registered in the PayNow proxy directory
 *  @param participantCode  bank participant code (e.g. "DBS", "OCBC") — None if not registered
 *  @param maskedAccount    masked bank account number for display — None if not available
 */
case class ProxyResolutionResult(
  registered:      Boolean,
  participantCode: Option[String],
  maskedAccount:   Option[String] = None,
)
