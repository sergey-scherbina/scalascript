package scalascript.payments.compliance.chainalysis

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.payments.compliance.*
import scala.concurrent.{Future, Await}
import scala.concurrent.duration.Duration
import scala.concurrent.ExecutionContext.global

class ChainalysisProviderTest extends AnyFunSuite with Matchers:

  private val config = ChainalysisConfig(apiKey = "test_key_abc")

  private def await[T](f: Future[T]): T = Await.result(f, Duration(5, "seconds"))

  private val individual = ComplianceEntity(
    name       = "0xdeadbeef",
    entityType = EntityType.Individual,
    externalId = Some("0xdeadbeef")
  )

  private val noAddrEntity = ComplianceEntity(
    name       = "Jane Smith",
    entityType = EntityType.Individual,
    country    = Some("US")
  )

  private val ethAddress = BlockchainAddress("0xabc123", "ETH", "ethereum", TransferDirection.Received)
  private val btcAddress = BlockchainAddress("1A2B3C", "BTC", "bitcoin", TransferDirection.Sent)

  private def provider(postResp: String = "{}", getResp: String = "{}"): ChainalysisProvider =
    new ChainalysisProvider(config):
      override protected def postJson(path: String, jsonBody: String): String = postResp
      override protected def getJson(path: String): String = getResp

  // ── id / displayName ──────────────────────────────────────────────────

  test("id is 'chainalysis'"):
    ChainalysisProvider(config).id shouldBe "chainalysis"

  test("displayName includes 'Chainalysis'"):
    ChainalysisProvider(config).displayName should include("Chainalysis")

  // ── screenTransfer ────────────────────────────────────────────────────

  test("screenTransfer: low risk score → Low risk level, riskScore preserved"):
    val json = """{"externalId":"tx_001","riskScore":5,"cluster":"Exchange: Coinbase"}"""
    val p    = provider(postResp = json)
    val result = await(p.screenTransfer(ethAddress)(using global))
    result.riskLevel  shouldBe RiskLevel.Low
    result.riskScore  shouldBe 5
    result.cluster    shouldBe Some("Exchange: Coinbase")
    result.address    shouldBe ethAddress

  test("screenTransfer: medium risk score (30–69) → Medium"):
    val json = """{"externalId":"tx_002","riskScore":45}"""
    val p    = provider(postResp = json)
    val result = await(p.screenTransfer(ethAddress)(using global))
    result.riskLevel shouldBe RiskLevel.Medium
    result.riskScore shouldBe 45

  test("screenTransfer: high risk score (70–89) → High"):
    val json = """{"externalId":"tx_003","riskScore":75}"""
    val p    = provider(postResp = json)
    val result = await(p.screenTransfer(ethAddress)(using global))
    result.riskLevel shouldBe RiskLevel.High

  test("screenTransfer: critical risk score (≥90) → Critical"):
    val json = """{"externalId":"tx_004","riskScore":95}"""
    val p    = provider(postResp = json)
    val result = await(p.screenTransfer(ethAddress)(using global))
    result.riskLevel shouldBe RiskLevel.Critical
    result.riskScore shouldBe 95

  test("screenTransfer: BTC SENT direction builds correct JSON"):
    val json = """{"externalId":"tx_005","riskScore":0}"""
    val p    = provider(postResp = json)
    val result = await(p.screenTransfer(btcAddress)(using global))
    result.address shouldBe btcAddress

  test("screenTransfer: API error → CheckFailed"):
    val failingProvider = new ChainalysisProvider(config):
      override protected def postJson(path: String, jsonBody: String): String =
        throw ComplianceError.CheckFailed("Chainalysis API returned 503")
    val ex = await(failingProvider.screenTransfer(ethAddress)(using global).failed)
    ex shouldBe a[ComplianceError.CheckFailed]

  test("screenTransfer: rate limit → RateLimitExceeded"):
    val failingProvider = new ChainalysisProvider(config):
      override protected def postJson(path: String, jsonBody: String): String =
        throw ComplianceError.RateLimitExceeded()
    val ex = await(failingProvider.screenTransfer(ethAddress)(using global).failed)
    ex shouldBe a[ComplianceError.RateLimitExceeded]

  test("screenTransfer: network error wrapped in CheckFailed"):
    val failingProvider = new ChainalysisProvider(config):
      override protected def postJson(path: String, jsonBody: String): String =
        throw new java.net.ConnectException("Connection refused")
    val ex = await(failingProvider.screenTransfer(ethAddress)(using global).failed)
    ex shouldBe a[ComplianceError.CheckFailed]

  // ── screenAml ─────────────────────────────────────────────────────────

  test("screenAml: entity with blockchain address in externalId → uses entity API"):
    val json = """{"riskScore":10}"""
    val p    = provider(getResp = json)
    val result = await(p.screenAml(individual)(using global))
    result.status    shouldBe ComplianceStatus.Approved
    result.riskLevel shouldBe RiskLevel.Low
    result.entity    shouldBe individual

  test("screenAml: entity with no blockchain address → Approved, Low, 0 matches"):
    val p = provider()
    val result = await(p.screenAml(noAddrEntity)(using global))
    result.status     shouldBe ComplianceStatus.Approved
    result.riskLevel  shouldBe RiskLevel.Low
    result.matchCount shouldBe 0

  test("screenAml: high risk entity address (score ≥70) → Rejected"):
    val json = """{"riskScore":80}"""
    val p    = provider(getResp = json)
    val result = await(p.screenAml(individual)(using global))
    result.status shouldBe ComplianceStatus.Rejected

  test("screenAml: entity address via metadata key 'blockchain_address'"):
    val metaEntity = noAddrEntity.copy(metadata = Map("blockchain_address" -> "0xfeed"))
    val json       = """{"riskScore":0}"""
    val p          = provider(getResp = json)
    val result     = await(p.screenAml(metaEntity)(using global))
    result.status  shouldBe ComplianceStatus.Approved

  // ── verifyKyc ─────────────────────────────────────────────────────────

  test("verifyKyc: always returns Approved with verifiedAt set"):
    val p      = provider()
    val result = await(p.verifyKyc(individual)(using global))
    result.status              shouldBe ComplianceStatus.Approved
    result.verifiedAt.isDefined shouldBe true

  test("verifyKyc: organization entity → Approved"):
    val org = ComplianceEntity("BlockFirm", EntityType.Organization)
    val p   = provider()
    val result = await(p.verifyKyc(org)(using global))
    result.status shouldBe ComplianceStatus.Approved

  // ── checkSanctions ─────────────────────────────────────────────────────

  test("checkSanctions: entity with address, low score → not matched, Approved"):
    val json = """{"riskScore":50}"""
    val p    = provider(getResp = json)
    val result = await(p.checkSanctions(individual)(using global))
    result.matched shouldBe false
    result.status  shouldBe ComplianceStatus.Approved

  test("checkSanctions: entity with address, score ≥90 → sanctioned, Rejected"):
    val json = """{"riskScore":95}"""
    val p    = provider(getResp = json)
    val result = await(p.checkSanctions(individual)(using global))
    result.matched      shouldBe true
    result.status       shouldBe ComplianceStatus.Rejected
    result.matchedLists should contain("Chainalysis Sanctioned Entities")

  test("checkSanctions: no blockchain address → not matched, Approved"):
    val p      = provider()
    val result = await(p.checkSanctions(noAddrEntity)(using global))
    result.matched shouldBe false
    result.status  shouldBe ComplianceStatus.Approved

  // ── getStatus ─────────────────────────────────────────────────────────

  test("getStatus: low risk score → Approved ComplianceReport"):
    val json = """{"riskScore":5}"""
    val p    = provider(getResp = json)
    val report = await(p.getStatus("tx_check_001")(using global))
    report.overallStatus shouldBe ComplianceStatus.Approved
    report.reportId      shouldBe Some("tx_check_001")

  test("getStatus: high risk score → Rejected ComplianceReport"):
    val json = """{"riskScore":75}"""
    val p    = provider(getResp = json)
    val report = await(p.getStatus("tx_check_002")(using global))
    report.overallStatus shouldBe ComplianceStatus.Rejected

  test("getStatus: API error → CheckFailed"):
    val failingProvider = new ChainalysisProvider(config):
      override protected def getJson(path: String): String =
        throw ComplianceError.CheckFailed("Not found")
    val ex = await(failingProvider.getStatus("bad_id")(using global).failed)
    ex shouldBe a[ComplianceError.CheckFailed]
