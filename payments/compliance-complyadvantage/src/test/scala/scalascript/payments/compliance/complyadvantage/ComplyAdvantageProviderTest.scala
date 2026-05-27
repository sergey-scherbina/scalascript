package scalascript.payments.compliance.complyadvantage

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.payments.compliance.*
import scala.concurrent.{Future, ExecutionContext, Await}
import scala.concurrent.duration.Duration
import scala.concurrent.ExecutionContext.global

class ComplyAdvantageProviderTest extends AnyFunSuite with Matchers:

  private val config = ComplyAdvantageConfig(apiKey = "test_key_123")

  private def await[T](f: Future[T]): T = Await.result(f, Duration(5, "seconds"))

  private val individual = ComplianceEntity(
    name        = "John Doe",
    entityType  = EntityType.Individual,
    country     = Some("US"),
    dateOfBirth = Some("1990-01-15")
  )

  private val organization = ComplianceEntity(
    name       = "Acme Corp",
    entityType = EntityType.Organization,
    country    = Some("DE")
  )

  private def provider(response: String): ComplyAdvantageProvider =
    new ComplyAdvantageProvider(config):
      override protected def postJson(path: String, jsonBody: String): String = response
      override protected def getJson(path: String): String = response

  // ── id / displayName ──────────────────────────────────────────────────

  test("id is 'complyadvantage'"):
    ComplyAdvantageProvider(config).id shouldBe "complyadvantage"

  test("displayName includes 'ComplyAdvantage'"):
    ComplyAdvantageProvider(config).displayName should include("ComplyAdvantage")

  // ── screenAml ─────────────────────────────────────────────────────────

  test("screenAml: low risk → Approved"):
    val json = """{"data":{"id":"search_123","risk_level":"low","total_hits":0,"hits":[]}}"""
    val p    = provider(json)
    val result = await(p.screenAml(individual)(using global))
    result.status     shouldBe ComplianceStatus.Approved
    result.riskLevel  shouldBe RiskLevel.Low
    result.matchCount shouldBe 0
    result.checkId    shouldBe Some("search_123")

  test("screenAml: high risk → Rejected"):
    val json = """{"data":{"id":"search_456","risk_level":"high","total_hits":1,"hits":[{"doc":{"name":"John Doe","score":0.9,"types":["pep"],"source_notes":{}}}]}}"""
    val p    = provider(json)
    val result = await(p.screenAml(individual)(using global))
    result.status    shouldBe ComplianceStatus.Rejected
    result.riskLevel shouldBe RiskLevel.High

  test("screenAml: medium risk → ManualReview"):
    val json = """{"data":{"id":"search_789","risk_level":"medium","total_hits":0,"hits":[]}}"""
    val p    = provider(json)
    val result = await(p.screenAml(individual)(using global))
    result.status shouldBe ComplianceStatus.ManualReview

  test("screenAml: very_high risk → Critical, Rejected"):
    val json = """{"data":{"id":"search_999","risk_level":"very_high","total_hits":1,"hits":[]}}"""
    val p    = provider(json)
    val result = await(p.screenAml(individual)(using global))
    result.status    shouldBe ComplianceStatus.Rejected
    result.riskLevel shouldBe RiskLevel.Critical

  test("screenAml: unknown risk level → Unknown, Approved"):
    val json = """{"data":{"id":"search_000","risk_level":"unknown","total_hits":0,"hits":[]}}"""
    val p    = provider(json)
    val result = await(p.screenAml(individual)(using global))
    result.riskLevel shouldBe RiskLevel.Unknown
    result.status    shouldBe ComplianceStatus.Approved

  test("screenAml: API error → CheckFailed"):
    val failingProvider = new ComplyAdvantageProvider(config):
      override protected def postJson(path: String, jsonBody: String): String =
        throw ComplianceError.CheckFailed("ComplyAdvantage API returned 500")
    val result = failingProvider.screenAml(individual)(using global)
    val ex = await(result.failed)
    ex shouldBe a[ComplianceError.CheckFailed]

  test("screenAml: network error wrapped in CheckFailed"):
    val failingProvider = new ComplyAdvantageProvider(config):
      override protected def postJson(path: String, jsonBody: String): String =
        throw new java.net.ConnectException("Connection refused")
    val result = failingProvider.screenAml(individual)(using global)
    val ex = await(result.failed)
    ex shouldBe a[ComplianceError.CheckFailed]

  test("screenAml: rate limit → RateLimitExceeded"):
    val failingProvider = new ComplyAdvantageProvider(config):
      override protected def postJson(path: String, jsonBody: String): String =
        throw ComplianceError.RateLimitExceeded(Some(30))
    val result = failingProvider.screenAml(individual)(using global)
    val ex = await(result.failed)
    ex shouldBe a[ComplianceError.RateLimitExceeded]

  test("screenAml: entity preserves externalId"):
    val json = """{"data":{"id":"search_ext","risk_level":"low","total_hits":0,"hits":[]}}"""
    val p    = provider(json)
    val entityWithId = individual.copy(externalId = Some("cust_abc_123"))
    val result = await(p.screenAml(entityWithId)(using global))
    result.entity.externalId shouldBe Some("cust_abc_123")

  // ── verifyKyc ─────────────────────────────────────────────────────────

  test("verifyKyc: low risk → Approved"):
    val json = """{"data":{"id":"kyc_111","risk_level":"low","total_hits":0,"hits":[]}}"""
    val p    = provider(json)
    val result = await(p.verifyKyc(individual)(using global))
    result.status            shouldBe ComplianceStatus.Approved
    result.checkId           shouldBe Some("kyc_111")
    result.verifiedAt.isDefined shouldBe true

  test("verifyKyc: high risk → Rejected"):
    val json = """{"data":{"id":"kyc_222","risk_level":"high","total_hits":1,"hits":[]}}"""
    val p    = provider(json)
    val result = await(p.verifyKyc(individual)(using global))
    result.status shouldBe ComplianceStatus.Rejected

  test("verifyKyc: organization entity → Approved on low risk"):
    val json = """{"data":{"id":"kyc_org","risk_level":"low","total_hits":0,"hits":[]}}"""
    val p    = provider(json)
    val result = await(p.verifyKyc(organization)(using global))
    result.status          shouldBe ComplianceStatus.Approved
    result.entity.name     shouldBe "Acme Corp"

  // ── checkSanctions ─────────────────────────────────────────────────────

  test("checkSanctions: no matches → not matched, Approved"):
    val json = """{"data":{"id":"sanc_100","risk_level":"low","total_hits":0,"hits":[]}}"""
    val p    = provider(json)
    val result = await(p.checkSanctions(individual)(using global))
    result.matched      shouldBe false
    result.status       shouldBe ComplianceStatus.Approved
    result.matchedLists shouldBe Nil

  test("checkSanctions: sanction hit → matched, Rejected"):
    val json = """{"data":{"id":"sanc_200","risk_level":"high","total_hits":1,"hits":[{"doc":{"name":"John Doe","score":0.98,"types":["sanction"],"source_notes":{"OFAC SDN":"match"}}}]}}"""
    val p    = provider(json)
    val result = await(p.checkSanctions(individual)(using global))
    result.matched shouldBe true
    result.status  shouldBe ComplianceStatus.Rejected

  // ── getStatus ─────────────────────────────────────────────────────────

  test("getStatus: returns ComplianceReport with checkId"):
    val json = """{"data":{"id":"rep_999","risk_level":"low","total_hits":0,"hits":[]}}"""
    val p    = provider(json)
    val report = await(p.getStatus("rep_999")(using global))
    report.reportId        shouldBe Some("rep_999")
    report.overallStatus   shouldBe ComplianceStatus.Approved
    report.aml.isDefined   shouldBe true

  test("getStatus: API error → CheckFailed"):
    val failingProvider = new ComplyAdvantageProvider(config):
      override protected def getJson(path: String): String =
        throw ComplianceError.CheckFailed("Not found")
    val result = failingProvider.getStatus("unknown_id")(using global)
    val ex = await(result.failed)
    ex shouldBe a[ComplianceError.CheckFailed]

  // ── fullReport (default impl) ─────────────────────────────────────────

  test("fullReport: all approved → Approved overall"):
    val json = """{"data":{"id":"full_001","risk_level":"low","total_hits":0,"hits":[]}}"""
    val p    = provider(json)
    val report = await(p.fullReport(individual)(using global))
    report.overallStatus         shouldBe ComplianceStatus.Approved
    report.aml.isDefined         shouldBe true
    report.kyc.isDefined         shouldBe true
    report.sanctions.isDefined   shouldBe true

  test("checkSanctions: entity type preserved in result"):
    val json = """{"data":{"id":"sanc_org","risk_level":"low","total_hits":0,"hits":[]}}"""
    val p    = provider(json)
    val result = await(p.checkSanctions(organization)(using global))
    result.entity.entityType shouldBe EntityType.Organization
    result.entity.name       shouldBe "Acme Corp"
