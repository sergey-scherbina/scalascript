package scalascript.payments.ukfps

import java.net.{URI}
import java.net.http.{HttpClient, HttpRequest as JHttpRequest}
import java.net.http.HttpResponse.BodyHandlers
import java.nio.charset.StandardCharsets
import java.time.Duration

/** Result of a UK Confirmation of Payee (CoP) name-check.
 *
 *  Pay.UK mandates CoP checks before sending Faster Payments to reduce
 *  authorised push payment (APP) fraud.  The aggregator's CoP service
 *  responds with one of these outcomes.
 *
 *  See specs/international-bank-rails.md §v1.55.3.
 */
enum CopResult:
  /** Account name exactly matches the bank's registered name. */
  case Matched
  /** Account exists but name is close — `suggestedName` is the bank-registered form. */
  case CloseMatch(suggestedName: String)
  /** Account exists but name does not match. */
  case NoMatch
  /** Account has been migrated by CASS (Current Account Switch Service) — redirect needed. */
  case AccountSwitched
  /** CoP service is temporarily unavailable — treat as non-fatal; re-check or proceed with caution. */
  case Unavailable

/** Confirmation of Payee (CoP) name-check client.
 *
 *  Calls the aggregator's CoP REST endpoint (e.g. TrueLayer, ClearBank, Modulr).
 *  The endpoint path and auth header are provided via `UkFpsConfig`.
 *
 *  Wire request shape (JSON):
 *  {{{
 *  {
 *    "sortCode":      "20-00-00",
 *    "accountNumber": "55779911",
 *    "name":          "John Smith"
 *  }
 *  }}}
 *
 *  Wire response `"result"` values map to `CopResult` cases.
 */
class ConfirmationOfPayee(config: UkFpsConfig):

  private val http: HttpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(10))
    .build()

  /** Perform a CoP name-check for the given sort-code, account-number, and payee name.
   *
   *  @param sortCode     6-digit UK sort code, format "XX-XX-XX" (e.g. "20-00-00")
   *  @param accountNumber 8-digit UK account number (e.g. "55779911")
   *  @param name         Account holder name to verify
   *  @return CopResult indicating the name-check outcome
   */
  def checkPayee(sortCode: String, accountNumber: String, name: String): CopResult =
    val json =
      s"""{
         |  "sortCode": "${escJson(sortCode)}",
         |  "accountNumber": "${escJson(accountNumber)}",
         |  "name": "${escJson(name)}"
         |}""".stripMargin
    val req = JHttpRequest.newBuilder(URI.create(s"${config.apiUrl}/cop/check"))
      .header("Authorization", s"Bearer ${config.apiKey}")
      .header("Content-Type", "application/json")
      .header("Accept", "application/json")
      .POST(JHttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
      .timeout(Duration.ofSeconds(15))
      .build()
    val resp = http.send(req, BodyHandlers.ofString(StandardCharsets.UTF_8))
    if resp.statusCode() == 503 || resp.statusCode() == 504 then
      return CopResult.Unavailable
    if resp.statusCode() >= 400 then
      throw new RuntimeException(s"CoP API error ${resp.statusCode()}: ${resp.body()}")
    parseResult(resp.body())

  // ── JSON response parsing ──────────────────────────────────────────────────

  private def parseResult(body: String): CopResult =
    val result = extractJsonString(body, "result").getOrElse("unavailable").toLowerCase
    result match
      case "matched"         => CopResult.Matched
      case "close_match"     =>
        val suggested = extractJsonString(body, "suggestedName").getOrElse("")
        CopResult.CloseMatch(suggested)
      case "no_match"        => CopResult.NoMatch
      case "account_switched" => CopResult.AccountSwitched
      case _                 => CopResult.Unavailable

  // ── Minimal JSON helpers ───────────────────────────────────────────────────

  private[ukfps] def extractJsonString(json: String, key: String): Option[String] =
    s""""$key"\\s*:\\s*"([^"\\\\]*)"""".r.findFirstMatchIn(json).map(_.group(1))

  private def escJson(s: String): String =
    s.replace("\\", "\\\\").replace("\"", "\\\"")
