package scalascript.x402.server

import scalascript.x402.*
import scalascript.server.{Request, Response}
import org.scalatest.funsuite.AnyFunSuite
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*
import ExecutionContext.Implicits.global
import java.util.Base64

class PaymentMiddlewareTest extends AnyFunSuite:

  private val testReq = PaymentRequirements(
    scheme      = PaymentScheme.Exact(1_000_000L),
    network     = Network.Base,
    asset       = Assets.USDC_BASE,
    payTo       = "0xpayTo",
    resource    = "/api/premium",
    description = "Premium access",
  )

  private val testConfig = PaymentConfig(
    requirements   = testReq,
    facilitator    = Facilitators.testnet(),
    nonceStore     = NonceStore.inMemory(),
    settlementMode = SettlementMode.Synchronous,
  )

  private def makeRequest(headers: Map[String, String] = Map.empty) =
    Request(
      method  = "GET",
      path    = "/api/premium",
      params  = Map.empty,
      query   = Map.empty,
      headers = headers,
      body    = "",
    )

  private val handler: Request => Future[Response] =
    _ => Future.successful(Response(200, body = "ok"))

  private def makePaymentHeader(payload: PaymentPayload): String =
    val auth = payload.authorization
    val json = ujson.Obj(
      "x402Version"   -> payload.x402Version,
      "scheme"        -> ujson.Obj("type" -> "exact", "amount" -> payload.scheme.asInstanceOf[PaymentScheme.Exact].amount.toString),
      "network"       -> payload.network.toString,
      "authorization" -> ujson.Obj(
        "from"        -> auth.from,
        "to"          -> auth.to,
        "value"       -> auth.value.toString,
        "validAfter"  -> auth.validAfter.toString,
        "validBefore" -> auth.validBefore.toString,
        "nonce"       -> auth.nonce,
      ),
      "signature"     -> payload.signature,
    ).toString
    Base64.getEncoder.encodeToString(json.getBytes("UTF-8"))

  private def makeValidPayload(nonce: String = "0x" + "ab" * 32) =
    PaymentPayload(
      scheme        = PaymentScheme.Exact(1_000_000L),
      network       = Network.Base,
      authorization = TransferAuthorization(
        from        = "0xfrom",
        to          = "0xpayTo",
        value       = BigInt(1_000_000),
        validAfter  = BigInt(0),
        validBefore = BigInt(9_999_999_999L),
        nonce       = nonce,
      ),
      signature     = "0x" + "cc" * 65,
    )

  // ── No payment header → 402 ──────────────────────────────────────────────────

  test("no X-Payment header → 402") {
    val mw   = withPayment(testConfig)(handler)
    val resp = Await.result(mw(makeRequest()), 5.seconds)
    assert(resp.status == 402)
    assert(resp.headers.getOrElse("Content-Type", "").contains("application/json"))
    assert(resp.body.contains("Payment Required"))
  }

  test("402 body contains requirements JSON") {
    val mw   = withPayment(testConfig)(handler)
    val resp = Await.result(mw(makeRequest()), 5.seconds)
    val j    = ujson.read(resp.body)
    assert(j("requirements")("payTo").str == "0xpayTo")
    assert(j("requirements")("network").str == "Base")
  }

  // ── Valid payment → 200 ──────────────────────────────────────────────────────

  test("valid X-Payment header → 200") {
    val payload = makeValidPayload()
    val encoded = makePaymentHeader(payload)
    val req     = makeRequest(Map("x-payment" -> encoded))
    val mw      = withPayment(testConfig)(handler)
    val resp    = Await.result(mw(req), 5.seconds)
    assert(resp.status == 200)
    assert(resp.body == "ok")
  }

  // ── Nonce replay → 402 ──────────────────────────────────────────────────────

  test("replayed nonce → 402") {
    val payload = makeValidPayload("0x" + "ee" * 32)
    val encoded = makePaymentHeader(payload)
    val req     = makeRequest(Map("x-payment" -> encoded))
    val mw      = withPayment(testConfig)(handler)
    // First request succeeds
    val resp1 = Await.result(mw(req), 5.seconds)
    assert(resp1.status == 200)
    // Replay is rejected
    val resp2 = Await.result(mw(req), 5.seconds)
    assert(resp2.status == 402)
    assert(resp2.body.contains("Nonce already used"))
  }

  // ── Facilitator failure → 402 ────────────────────────────────────────────────

  test("failing facilitator verify → 402") {
    val failFac = new Facilitator:
      def verify(p: PaymentPayload, r: PaymentRequirements) =
        Future.successful(VerifyResult.Fail("bad signature"))
      def settle(p: PaymentPayload, r: PaymentRequirements) =
        Future.successful(SettleResult.Ok("0xtx"))
    val config  = testConfig.copy(facilitator = failFac)
    val payload = makeValidPayload("0x" + "dd" * 32)
    val encoded = makePaymentHeader(payload)
    val req     = makeRequest(Map("x-payment" -> encoded))
    val mw      = withPayment(config)(handler)
    val resp    = Await.result(mw(req), 5.seconds)
    assert(resp.status == 402)
    assert(resp.body.contains("bad signature"))
  }

  test("failing facilitator settle (sync mode) → 402") {
    val failFac = new Facilitator:
      def verify(p: PaymentPayload, r: PaymentRequirements) =
        Future.successful(VerifyResult.Ok)
      def settle(p: PaymentPayload, r: PaymentRequirements) =
        Future.successful(SettleResult.Fail("chain unavailable"))
    val config  = testConfig.copy(
      facilitator = failFac,
      nonceStore  = NonceStore.inMemory(),
    )
    val payload = makeValidPayload("0x" + "bb" * 32)
    val encoded = makePaymentHeader(payload)
    val req     = makeRequest(Map("x-payment" -> encoded))
    val mw      = withPayment(config)(handler)
    val resp    = Await.result(mw(req), 5.seconds)
    assert(resp.status == 402)
    assert(resp.body.contains("chain unavailable"))
  }

  // ── Async settlement mode → 200 immediately ──────────────────────────────────

  test("async settlement mode: verify Ok → 200 without waiting for settle") {
    var settled = 0
    val queue   = SettlementQueue.inMemory()
    val fac = new Facilitator:
      def verify(p: PaymentPayload, r: PaymentRequirements) = Future.successful(VerifyResult.Ok)
      def settle(p: PaymentPayload, r: PaymentRequirements) =
        settled += 1; Future.successful(SettleResult.Ok("0xtx"))
    val config  = testConfig.copy(
      facilitator    = fac,
      nonceStore     = NonceStore.inMemory(),
      settlementMode = SettlementMode.Async(queue),
    )
    val payload = makeValidPayload("0x" + "11" * 32)
    val encoded = makePaymentHeader(payload)
    val req     = makeRequest(Map("x-payment" -> encoded))
    val mw      = withPayment(config)(handler)
    val resp    = Await.result(mw(req), 5.seconds)
    assert(resp.status == 200)
    assert(settled == 0)   // settle not called synchronously
    Await.result(queue.process(fac), 5.seconds)
    assert(settled == 1)
  }

  // ── Malformed X-Payment header → 402 ─────────────────────────────────────────

  test("malformed X-Payment header → 402 (not a crash)") {
    val req  = makeRequest(Map("x-payment" -> "not-valid-base64!!!"))
    val mw   = withPayment(testConfig)(handler)
    val resp = Await.result(mw(req), 5.seconds)
    assert(resp.status == 402)
    assert(resp.body.contains("Payment"))
  }

  // ── onSettled callback ────────────────────────────────────────────────────────

  test("onSettled callback fires after successful payment") {
    var callbackFired = false
    val config = testConfig.copy(
      nonceStore = NonceStore.inMemory(),
      onSettled  = (_, _) => Future { callbackFired = true },
    )
    val payload = makeValidPayload("0x" + "22" * 32)
    val encoded = makePaymentHeader(payload)
    val req     = makeRequest(Map("x-payment" -> encoded))
    val mw      = withPayment(config)(handler)
    Await.result(mw(req), 5.seconds)
    assert(callbackFired)
  }

  // ── JSON serialization round-trip ─────────────────────────────────────────────

  test("requirementsBody is valid JSON with expected fields") {
    val body = Json.requirementsBody(testReq)
    val j    = ujson.read(body)
    assert(j("x402Version").num.toInt == 1)
    assert(j("network").str           == "Base")
    assert(j("chainId").num.toInt     == 8453)
    assert(j("payTo").str             == "0xpayTo")
    assert(j("asset")("symbol").str   == "USDC")
    assert(j("asset")("decimals").num.toInt == 6)
  }
