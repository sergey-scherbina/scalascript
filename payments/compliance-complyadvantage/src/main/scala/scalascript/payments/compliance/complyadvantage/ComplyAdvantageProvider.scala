package scalascript.payments.compliance.complyadvantage

import scalascript.payments.compliance.*
import java.net.URI
import java.net.http.{HttpClient, HttpRequest as JHttpRequest}
import java.net.http.HttpResponse.BodyHandlers
import java.nio.charset.StandardCharsets
import java.time.{Duration, Instant}
import scala.concurrent.{Future, ExecutionContext}

/** ComplyAdvantage REST API v1 adapter.
 *
 *  Endpoint: POST `https://api.complyadvantage.com/searches`
 *
 *  Auth: Bearer token (`Authorization: Token <apiKey>`).
 *
 *  Wire format: JSON. Fuzz search by entity name + optional date-of-birth / country.
 *
 *  Response: `{ "data": { "id": "...", "hits": [...], "risk_level": "low|medium|high|very_high" } }`.
 *
 *  KYC: ComplyAdvantage does not provide KYC identity document verification;
 *  `verifyKyc` performs a PEP + adverse-media search and returns based on risk level.
 *
 *  Sanctions: searches PEP + Sanctions profiles; `checkSanctions` extracts
 *  "sanction" typed hits from the full search result.
 *
 *  See `docs/compliance-provider.md §complyadvantage`.
 */
class ComplyAdvantageProvider(config: ComplyAdvantageConfig) extends ComplianceProvider:

  def id:          String = "complyadvantage"
  def displayName: String = "ComplyAdvantage v1"

  private val baseUrl = config.baseUrl.stripSuffix("/")

  // ── Injectable HTTP method (overridden in tests) ──────────────────────

  protected def postJson(path: String, jsonBody: String): String =
    val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()
    val req    = JHttpRequest.newBuilder()
      .uri(URI.create(s"$baseUrl$path"))
      .header("Authorization", s"Token ${config.apiKey}")
      .header("Content-Type",  "application/json")
      .POST(JHttpRequest.BodyPublishers.ofString(jsonBody))
      .build()
    val resp = client.send(req, BodyHandlers.ofString(StandardCharsets.UTF_8))
    if resp.statusCode() == 429 then
      val retryAfter = resp.headers.firstValueAsLong("Retry-After").orElse(-1L)
      throw ComplianceError.RateLimitExceeded(if retryAfter > 0 then Some(retryAfter.toInt) else None)
    if resp.statusCode() >= 400 then
      throw ComplianceError.CheckFailed(s"ComplyAdvantage API returned ${resp.statusCode()}: ${resp.body().take(200)}")
    resp.body()

  protected def getJson(path: String): String =
    val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()
    val req    = JHttpRequest.newBuilder()
      .uri(URI.create(s"$baseUrl$path"))
      .header("Authorization", s"Token ${config.apiKey}")
      .GET()
      .build()
    val resp = client.send(req, BodyHandlers.ofString(StandardCharsets.UTF_8))
    if resp.statusCode() >= 400 then
      throw ComplianceError.CheckFailed(s"ComplyAdvantage API returned ${resp.statusCode()}: ${resp.body().take(200)}")
    resp.body()

  // ── ComplianceProvider interface ──────────────────────────────────────

  def screenAml(entity: ComplianceEntity)(using ExecutionContext): Future[AmlResult] =
    Future {
      val body    = postJson("/searches", buildSearchJson(entity, searchTypes = "pep,sanction,adverse-media"))
      parseAmlResult(body, entity)
    }.recoverWith {
      case e: ComplianceError => Future.failed(e)
      case e                  => Future.failed(ComplianceError.CheckFailed(e.getMessage, e))
    }

  def verifyKyc(entity: ComplianceEntity)(using ExecutionContext): Future[KycResult] =
    Future {
      val body    = postJson("/searches", buildSearchJson(entity, searchTypes = "pep,sanction"))
      parseKycResult(body, entity)
    }.recoverWith {
      case e: ComplianceError => Future.failed(e)
      case e                  => Future.failed(ComplianceError.CheckFailed(e.getMessage, e))
    }

  def checkSanctions(entity: ComplianceEntity)(using ExecutionContext): Future[SanctionsResult] =
    Future {
      val body    = postJson("/searches", buildSearchJson(entity, searchTypes = "sanction"))
      parseSanctionsResult(body, entity)
    }.recoverWith {
      case e: ComplianceError => Future.failed(e)
      case e                  => Future.failed(ComplianceError.CheckFailed(e.getMessage, e))
    }

  def getStatus(checkId: String)(using ExecutionContext): Future[ComplianceReport] =
    Future {
      val body = getJson(s"/searches/$checkId")
      parseStatusReport(body, checkId)
    }.recoverWith {
      case e: ComplianceError => Future.failed(e)
      case e                  => Future.failed(ComplianceError.CheckFailed(e.getMessage, e))
    }

  // ── Request builder ────────────────────────────────────────────────────

  private def buildSearchJson(entity: ComplianceEntity, searchTypes: String): String =
    val fuzz    = config.fuzzinessThreshold
    val dob     = entity.dateOfBirth.map(d => s""","birth_year":"${d.take(4)}"""").getOrElse("")
    val country = entity.country.map(c => s""","country_codes":["$c"]""").getOrElse("")

    s"""{
      "search_term": "${jsonEscape(entity.name)}",
      "fuzziness": $fuzz,
      "search_profile": "${config.searchProfile}",
      "filters": {
        "types": [${searchTypes.split(",").map(t => s""""${t.trim}"""").mkString(",")}]
        $country
      }$dob
    }"""

  // ── Response parsers ───────────────────────────────────────────────────

  private def parseAmlResult(body: String, entity: ComplianceEntity): AmlResult =
    val checkId   = extractStr(body, "\"id\"")
    val riskLevel = parseRiskLevel(extractStr(body, "\"risk_level\"").getOrElse("unknown"))
    val hits      = parseHits(body)

    val status = riskLevel match
      case RiskLevel.Critical | RiskLevel.High => ComplianceStatus.Rejected
      case RiskLevel.Medium                    => ComplianceStatus.ManualReview
      case _                                   => ComplianceStatus.Approved

    AmlResult(
      entity      = entity,
      status      = status,
      riskLevel   = riskLevel,
      matchCount  = hits.length,
      matches     = hits,
      checkId     = checkId
    )

  private def parseKycResult(body: String, entity: ComplianceEntity): KycResult =
    val checkId   = extractStr(body, "\"id\"")
    val riskLevel = parseRiskLevel(extractStr(body, "\"risk_level\"").getOrElse("unknown"))

    val status = riskLevel match
      case RiskLevel.Critical | RiskLevel.High => ComplianceStatus.Rejected
      case RiskLevel.Medium                    => ComplianceStatus.ManualReview
      case _                                   => ComplianceStatus.Approved

    KycResult(
      entity    = entity,
      status    = status,
      checkId   = checkId,
      verifiedAt = Some(Instant.now())
    )

  private def parseSanctionsResult(body: String, entity: ComplianceEntity): SanctionsResult =
    val checkId = extractStr(body, "\"id\"")
    val hits    = parseHits(body).filter(h => h.listType.toLowerCase.contains("sanction"))

    val matched = hits.nonEmpty
    val lists   = hits.map(_.listSource).distinct
    val status  = if matched then ComplianceStatus.Rejected else ComplianceStatus.Approved

    SanctionsResult(
      entity       = entity,
      status       = status,
      matched      = matched,
      matchedLists = lists,
      checkId      = checkId
    )

  private def parseStatusReport(body: String, checkId: String): ComplianceReport =
    val dummyEntity = ComplianceEntity("(retrieved)", EntityType.Individual)
    val amlResult   = parseAmlResult(body, dummyEntity)
    ComplianceReport(
      entity        = dummyEntity,
      overallStatus = amlResult.status,
      aml           = Some(amlResult),
      reportId      = Some(checkId)
    )

  private def parseHits(body: String): List[AmlMatch] =
    val namePattern  = """"name"\s*:\s*"([^"]+)"""".r
    val scorePattern = """"score"\s*:\s*([\d.]+)""".r
    val typePattern  = """"types"\s*:\s*\[([^\]]+)\]""".r
    val sourcePattern = """"source_notes"\s*:\s*\{([^}]+)\}""".r

    val names   = namePattern.findAllMatchIn(body).map(_.group(1)).toList
    val scores  = scorePattern.findAllMatchIn(body).map(m => m.group(1).toDoubleOption.getOrElse(0.0)).toList
    val types   = typePattern.findAllMatchIn(body).map(_.group(1).replace("\"", "").trim).toList
    val sources = sourcePattern.findAllMatchIn(body).map(_.group(1)).toList

    names.zipWithIndex.map { case (name, i) =>
      AmlMatch(
        matchedName = name,
        matchScore  = if i < scores.length then scores(i) else 0.0,
        listType    = if i < types.length then types(i).split(",").headOption.getOrElse("Unknown").trim else "Unknown",
        listSource  = if i < sources.length then sources(i).take(50) else "ComplyAdvantage"
      )
    }

  private def parseRiskLevel(raw: String): RiskLevel = raw.toLowerCase match
    case "very_high" | "critical" => RiskLevel.Critical
    case "high"                   => RiskLevel.High
    case "medium"                 => RiskLevel.Medium
    case "low"                    => RiskLevel.Low
    case _                        => RiskLevel.Unknown

  // ── Helpers ────────────────────────────────────────────────────────────

  private def jsonEscape(s: String): String =
    s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")

  private def extractStr(body: String, key: String): Option[String] =
    val pat = s"""$key\\s*:\\s*"([^"]+)"""".r
    pat.findFirstMatchIn(body).map(_.group(1))


/** ComplyAdvantage adapter configuration. */
case class ComplyAdvantageConfig(
  apiKey:               String,
  baseUrl:              String  = "https://api.complyadvantage.com",
  fuzzinessThreshold:   Double  = 0.7,
  searchProfile:        String  = "financial-crime"
)

object ComplyAdvantageConfig:
  def fromEnv: ComplyAdvantageConfig =
    ComplyAdvantageConfig(
      apiKey             = sys.env.getOrElse("COMPLYADVANTAGE_API_KEY", ""),
      baseUrl            = sys.env.getOrElse("COMPLYADVANTAGE_BASE_URL", "https://api.complyadvantage.com"),
      fuzzinessThreshold = sys.env.get("COMPLYADVANTAGE_FUZZINESS").flatMap(_.toDoubleOption).getOrElse(0.7),
      searchProfile      = sys.env.getOrElse("COMPLYADVANTAGE_PROFILE", "financial-crime")
    )
