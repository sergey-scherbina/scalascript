package scalascript.x402.server

import scalascript.x402.*
import scalascript.server.{Request, Response}
import org.scalatest.funsuite.AnyFunSuite
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*
import ExecutionContext.Implicits.global
import java.util.Base64

class StreamPaymentTest extends AnyFunSuite:

  private val streamScheme = PaymentScheme.Stream(
    ratePerUnit = 500_000L,
    unitName    = "request",
    maxUnits    = 100,
    maxAmount   = 5_000_000L,
  )

  private val testReq = PaymentRequirements(
    scheme      = streamScheme,
    network     = Network.Base,
    asset       = Assets.USDC_BASE,
    payTo       = "0xpayTo",
    resource    = "/api/stream",
    description = "Metered API access",
  )

  private val streamConfig = PaymentConfig(
    requirements   = testReq,
    facilitator    = Facilitators.testnet(),
    nonceStore     = NonceStore.inMemory(),
    settlementMode = SettlementMode.Synchronous,
  )

  private val handler: Request => Future[Response] =
    _ => Future.successful(Response(200, body = "data"))

  private def makeStreamHeader(
    authValue: BigInt,
    nonce:     String = "0x" + "ab" * 32,
    extraHeaders: Map[String, String] = Map.empty,
  ): (Map[String, String], String) =
    val auth = TransferAuthorization(
      from        = "0xfrom",
      to          = "0xpayTo",
      value       = authValue,
      validAfter  = BigInt(0),
      validBefore = BigInt(9_999_999_999L),
      nonce       = nonce,
    )
    val json = ujson.Obj(
      "x402Version"   -> 1,
      "scheme"        -> ujson.Obj(
        "type"        -> "stream",
        "ratePerUnit" -> "500000",
        "unitName"    -> "request",
        "maxUnits"    -> 100,
        "maxAmount"   -> "5000000",
      ),
      "network"       -> "Base",
      "authorization" -> ujson.Obj(
        "from"        -> auth.from,
        "to"          -> auth.to,
        "value"       -> authValue.toString,
        "validAfter"  -> auth.validAfter.toString,
        "validBefore" -> auth.validBefore.toString,
        "nonce"       -> nonce,
      ),
      "signature"     -> ("0x" + "cc" * 65),
    ).toString
    val encoded = Base64.getEncoder.encodeToString(json.getBytes("UTF-8"))
    val headers = Map("x-payment" -> encoded) ++ extraHeaders
    (headers, encoded)

  private def makeRequest(headers: Map[String, String]) =
    Request("GET", "/api/stream", Map.empty, Map.empty, headers, "")

  // ── Stream amount validation ──────────────────────────────────────────────────

  test("Stream: correct authorization value (ratePerUnit) → 200") {
    val (headers, _) = makeStreamHeader(BigInt(500_000))   // 1 unit = ratePerUnit
    val mw   = withPayment(streamConfig)(handler)
    val resp = Await.result(mw(makeRequest(headers)), 5.seconds)
    assert(resp.status == 200)
  }

  test("Stream: wrong authorization value → 402") {
    val (headers, _) = makeStreamHeader(BigInt(200_000))   // not ratePerUnit
    val mw   = withPayment(streamConfig)(handler)
    val resp = Await.result(mw(makeRequest(headers)), 5.seconds)
    assert(resp.status == 402)
    assert(resp.body.contains("Stream"))
  }

  test("Stream: authorization value exceeds maxAmount → 402") {
    val (headers, _) = makeStreamHeader(BigInt(6_000_000))   // > maxAmount
    val mw   = withPayment(streamConfig)(handler)
    val resp = Await.result(mw(makeRequest(headers)), 5.seconds)
    assert(resp.status == 402)
    assert(resp.body.contains("maxAmount"))
  }

  test("Stream: multi-unit request with X-Units header → 200") {
    val (headers, _) = makeStreamHeader(
      BigInt(1_500_000),  // 3 units * 500_000
      nonce       = "0x" + "bb" * 32,
      extraHeaders = Map("x-units" -> "3"),
    )
    val mw   = withPayment(streamConfig)(handler)
    val resp = Await.result(mw(makeRequest(headers)), 5.seconds)
    assert(resp.status == 200)
  }

  test("Stream: X-Units=2 with wrong amount → 402") {
    val (headers, _) = makeStreamHeader(
      BigInt(500_000),   // only 1 unit, but 2 requested
      nonce        = "0x" + "cc" * 32,
      extraHeaders = Map("x-units" -> "2"),
    )
    val mw   = withPayment(streamConfig)(handler)
    val resp = Await.result(mw(makeRequest(headers)), 5.seconds)
    assert(resp.status == 402)
    assert(resp.body.contains("expected 1000000"))
  }

  test("Stream: no X-Payment header → 402 with Stream requirements") {
    val mw   = withPayment(streamConfig)(handler)
    val resp = Await.result(mw(makeRequest(Map.empty)), 5.seconds)
    assert(resp.status == 402)
    val j = ujson.read(resp.body)
    assert(j("requirements")("scheme")("type").str == "stream")
  }

  // ── withStreamPayment ─────────────────────────────────────────────────────────

  test("withStreamPayment: handler receives unit count from X-Units header") {
    var receivedUnits = 0
    val unitHandler: (Request, Int) => Future[Response] =
      (_, units) =>
        receivedUnits = units
        Future.successful(Response(200, body = "stream ok"))

    val (headers, _) = makeStreamHeader(
      BigInt(1_500_000),
      nonce        = "0x" + "dd" * 32,
      extraHeaders = Map("x-units" -> "3"),
    )
    val mw   = withStreamPayment(streamConfig)(unitHandler)
    val resp = Await.result(mw(makeRequest(headers)), 5.seconds)
    assert(resp.status == 200)
    assert(receivedUnits == 3)
  }

  test("withStreamPayment: default units = 1 when X-Units absent") {
    var receivedUnits = -1
    val unitHandler: (Request, Int) => Future[Response] =
      (_, units) =>
        receivedUnits = units
        Future.successful(Response(200, body = "ok"))

    val (headers, _) = makeStreamHeader(BigInt(500_000), "0x" + "ee" * 32)
    val mw   = withStreamPayment(streamConfig)(unitHandler)
    val resp = Await.result(mw(makeRequest(headers)), 5.seconds)
    assert(resp.status == 200)
    assert(receivedUnits == 1)
  }

  test("withStreamPayment: defaultUnits parameter is used when X-Units absent") {
    var receivedUnits = -1
    val unitHandler: (Request, Int) => Future[Response] =
      (_, units) =>
        receivedUnits = units
        Future.successful(Response(200, body = "ok"))

    // defaultUnits=2 → expected = 500_000 * 2 = 1_000_000
    val (headers, _) = makeStreamHeader(BigInt(1_000_000), "0x" + "ff" * 32)
    val mw   = withStreamPayment(streamConfig, defaultUnits = 2)(unitHandler)
    val resp = Await.result(mw(makeRequest(headers)), 5.seconds)
    assert(resp.status == 200)
    assert(receivedUnits == 2)
  }

  // ── Exact scheme: Stream validation is skipped ────────────────────────────────

  test("Exact scheme: validateStream is not applied") {
    val exactConfig = streamConfig.copy(requirements = testReq.copy(
      scheme = PaymentScheme.Exact(BigInt(1_000_000))
    ))
    val auth = TransferAuthorization(
      from = "0xfrom", to = "0xpayTo",
      value = BigInt(1_000_000), validAfter = BigInt(0),
      validBefore = BigInt(9_999_999_999L), nonce = "0x" + "11" * 32,
    )
    val json = ujson.Obj(
      "x402Version"   -> 1,
      "scheme"        -> ujson.Obj("type" -> "exact", "amount" -> "1000000"),
      "network"       -> "Base",
      "authorization" -> ujson.Obj(
        "from"        -> auth.from,
        "to"          -> auth.to,
        "value"       -> "1000000",
        "validAfter"  -> "0",
        "validBefore" -> "9999999999",
        "nonce"       -> auth.nonce,
      ),
      "signature"     -> ("0x" + "cc" * 65),
    ).toString
    val encoded = Base64.getEncoder.encodeToString(json.getBytes("UTF-8"))
    val mw   = withPayment(exactConfig)(handler)
    val resp = Await.result(mw(makeRequest(Map("x-payment" -> encoded))), 5.seconds)
    assert(resp.status == 200)
  }
