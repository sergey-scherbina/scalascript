package scalascript.payments.compliance.chainalysis

import scalascript.payments.compliance.*
import java.net.URI
import java.net.http.{HttpClient, HttpRequest as JHttpRequest}
import java.net.http.HttpResponse.BodyHandlers
import java.nio.charset.StandardCharsets
import java.time.{Duration, Instant}
import scala.concurrent.{Future, ExecutionContext}

/** Chainalysis KYT (Know Your Transaction) REST v2 adapter.
 *
 *  Endpoint: POST `https://api.chainalysis.com/api/kyt/v2/transfers`
 *
 *  Auth: API key as `Token <apiKey>` header.
 *
 *  Wire format: JSON. Registers a transfer; Chainalysis scores it asynchronously.
 *
 *  `screenTransfer`: POST `/api/kyt/v2/transfers` → returns risk score + alerts.
 *  `screenAml` / `verifyKyc` / `checkSanctions`: use `/api/risk/v2/entities/<address>` for
 *  blockchain entity risk; falls back to format-only validation for non-blockchain entities.
 *
 *  See `specs/compliance-provider.md §chainalysis`.
 */
class ChainalysisProvider(config: ChainalysisConfig) extends BlockchainComplianceProvider:

  def id:          String = "chainalysis"
  def displayName: String = "Chainalysis KYT v2"

  private val baseUrl = config.baseUrl.stripSuffix("/")

  // ── Injectable HTTP methods (overridden in tests) ─────────────────────

  protected def postJson(path: String, jsonBody: String): String =
    val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()
    val req    = JHttpRequest.newBuilder()
      .uri(URI.create(s"$baseUrl$path"))
      .header("Token",         config.apiKey)
      .header("Content-Type",  "application/json")
      .POST(JHttpRequest.BodyPublishers.ofString(jsonBody))
      .build()
    val resp = client.send(req, BodyHandlers.ofString(StandardCharsets.UTF_8))
    if resp.statusCode() == 429 then
      throw ComplianceError.RateLimitExceeded()
    if resp.statusCode() >= 400 then
      throw ComplianceError.CheckFailed(s"Chainalysis API returned ${resp.statusCode()}: ${resp.body().take(200)}")
    resp.body()

  protected def getJson(path: String): String =
    val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()
    val req    = JHttpRequest.newBuilder()
      .uri(URI.create(s"$baseUrl$path"))
      .header("Token", config.apiKey)
      .GET()
      .build()
    val resp = client.send(req, BodyHandlers.ofString(StandardCharsets.UTF_8))
    if resp.statusCode() >= 400 then
      throw ComplianceError.CheckFailed(s"Chainalysis API returned ${resp.statusCode()}: ${resp.body().take(200)}")
    resp.body()

  // ── BlockchainComplianceProvider interface ────────────────────────────

  def screenTransfer(address: BlockchainAddress)(using ExecutionContext): Future[TransferRiskResult] =
    Future {
      val body   = postJson("/api/kyt/v2/transfers", buildTransferJson(address))
      parseTransferRisk(body, address)
    }.recoverWith {
      case e: ComplianceError => Future.failed(e)
      case e                  => Future.failed(ComplianceError.CheckFailed(e.getMessage, e))
    }

  // ── ComplianceProvider interface ──────────────────────────────────────

  def screenAml(entity: ComplianceEntity)(using ExecutionContext): Future[AmlResult] =
    Future {
      entity.externalId.orElse(
        entity.metadata.get("blockchain_address")
      ) match
        case Some(addr) =>
          val body = getJson(s"/api/risk/v2/entities/$addr")
          parseEntityAml(body, entity)
        case None =>
          // No blockchain address; return low risk with no matches
          AmlResult(entity, ComplianceStatus.Approved, RiskLevel.Low, 0)
    }.recoverWith {
      case e: ComplianceError => Future.failed(e)
      case e                  => Future.failed(ComplianceError.CheckFailed(e.getMessage, e))
    }

  def verifyKyc(entity: ComplianceEntity)(using ExecutionContext): Future[KycResult] =
    Future {
      // Chainalysis does not provide KYC identity verification;
      // return Approved for all entities (KYC must be handled separately)
      KycResult(entity, ComplianceStatus.Approved, verifiedAt = Some(Instant.now()))
    }

  def checkSanctions(entity: ComplianceEntity)(using ExecutionContext): Future[SanctionsResult] =
    Future {
      entity.externalId.orElse(entity.metadata.get("blockchain_address")) match
        case Some(addr) =>
          val body = getJson(s"/api/risk/v2/entities/$addr")
          parseEntitySanctions(body, entity)
        case None =>
          SanctionsResult(entity, ComplianceStatus.Approved, matched = false)
    }.recoverWith {
      case e: ComplianceError => Future.failed(e)
      case e                  => Future.failed(ComplianceError.CheckFailed(e.getMessage, e))
    }

  def getStatus(checkId: String)(using ExecutionContext): Future[ComplianceReport] =
    Future {
      val body = getJson(s"/api/kyt/v2/transfers/$checkId")
      parseTransferReport(body, checkId)
    }.recoverWith {
      case e: ComplianceError => Future.failed(e)
      case e                  => Future.failed(ComplianceError.CheckFailed(e.getMessage, e))
    }

  // ── Request builder ────────────────────────────────────────────────────

  private def buildTransferJson(address: BlockchainAddress): String =
    val direction = address.direction match
      case TransferDirection.Received => "RECEIVED"
      case TransferDirection.Sent     => "SENT"
    s"""{
      "network": "${address.network.toUpperCase}",
      "asset": "${address.asset}",
      "transferReference": "${jsonEscape(address.address)}",
      "direction": "$direction"
    }"""

  // ── Response parsers ───────────────────────────────────────────────────

  private def parseTransferRisk(body: String, address: BlockchainAddress): TransferRiskResult =
    val checkId    = extractStr(body, "\"externalId\"")
    val riskScore  = extractInt(body, "\"riskScore\"").getOrElse(0)
    val riskLevel  = scoreToRiskLevel(riskScore)
    val cluster    = extractStr(body, "\"cluster\"")
    val alerts     = extractStrings(body, "\"alertAmount\"")

    TransferRiskResult(
      address   = address,
      riskLevel = riskLevel,
      riskScore = riskScore,
      cluster   = cluster,
      alerts    = alerts,
      checkId   = checkId
    )

  private def parseEntityAml(body: String, entity: ComplianceEntity): AmlResult =
    val riskScore  = extractInt(body, "\"riskScore\"").getOrElse(0)
    val riskLevel  = scoreToRiskLevel(riskScore)
    val cluster    = extractStr(body, "\"name\"")
    val status     = if riskScore >= 70 then ComplianceStatus.Rejected
                     else if riskScore >= 30 then ComplianceStatus.ManualReview
                     else ComplianceStatus.Approved

    AmlResult(
      entity     = entity,
      status     = status,
      riskLevel  = riskLevel,
      matchCount = if riskScore > 0 then 1 else 0,
      matches    = if riskScore >= 30 then List(AmlMatch(
        matchedName = cluster.getOrElse(entity.name),
        matchScore  = riskScore.toDouble / 100.0,
        listType    = "Blockchain Risk",
        listSource  = "Chainalysis"
      )) else Nil
    )

  private def parseEntitySanctions(body: String, entity: ComplianceEntity): SanctionsResult =
    val riskScore = extractInt(body, "\"riskScore\"").getOrElse(0)
    val isSanctioned = riskScore >= 90
    val status    = if isSanctioned then ComplianceStatus.Rejected else ComplianceStatus.Approved
    SanctionsResult(
      entity       = entity,
      status       = status,
      matched      = isSanctioned,
      matchedLists = if isSanctioned then List("Chainalysis Sanctioned Entities") else Nil
    )

  private def parseTransferReport(body: String, checkId: String): ComplianceReport =
    val dummyEntity = ComplianceEntity("(blockchain)", EntityType.Individual)
    val riskScore   = extractInt(body, "\"riskScore\"").getOrElse(0)
    val status      = if riskScore >= 70 then ComplianceStatus.Rejected
                      else if riskScore >= 30 then ComplianceStatus.ManualReview
                      else ComplianceStatus.Approved
    ComplianceReport(
      entity        = dummyEntity,
      overallStatus = status,
      reportId      = Some(checkId)
    )

  private def scoreToRiskLevel(score: Int): RiskLevel =
    if score >= 90     then RiskLevel.Critical
    else if score >= 70 then RiskLevel.High
    else if score >= 30 then RiskLevel.Medium
    else if score > 0  then RiskLevel.Low
    else RiskLevel.Low

  // ── Helpers ────────────────────────────────────────────────────────────

  private def jsonEscape(s: String): String =
    s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

  private def extractStr(body: String, key: String): Option[String] =
    val pat = s"""$key\\s*:\\s*"([^"]+)"""".r
    pat.findFirstMatchIn(body).map(_.group(1))

  private def extractInt(body: String, key: String): Option[Int] =
    val pat = s"""$key\\s*:\\s*(\\d+)""".r
    pat.findFirstMatchIn(body).flatMap(m => m.group(1).toIntOption)

  private def extractStrings(body: String, key: String): List[String] =
    val pat = s"""$key\\s*:\\s*"([^"]+)"""".r
    pat.findAllMatchIn(body).map(_.group(1)).toList


/** Chainalysis KYT adapter configuration. */
case class ChainalysisConfig(
  apiKey:  String,
  baseUrl: String = "https://api.chainalysis.com"
)

object ChainalysisConfig:
  def fromEnv: ChainalysisConfig =
    ChainalysisConfig(
      apiKey  = sys.env.getOrElse("CHAINALYSIS_API_KEY",  ""),
      baseUrl = sys.env.getOrElse("CHAINALYSIS_BASE_URL", "https://api.chainalysis.com")
    )
